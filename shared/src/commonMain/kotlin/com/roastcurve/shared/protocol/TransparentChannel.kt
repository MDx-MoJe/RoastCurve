package com.roastcurve.shared.protocol

import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * 透传通道：App --(RTU 裸帧)--> 透传桥接器/模块 --RS485--> 温控器。
 *
 * 与 ModbusTcpChannel 的区别：编解码用 ModbusRtu（RTU 裸帧 + CRC16），
 * 传输层用 ByteTransport 抽象（TCP 透传 / BLE 透传共用）。
 * 轮询、重连、计时逻辑与 ModbusTcpChannel 一致。
 */
class TransparentChannel(
    override val name: String,
    private val transport: ByteTransport,
    private val slaveId: Int = ModbusRtu.Tc4s.DEFAULT_SLAVE_ID,
    private val pollIntervalMs: Long = 1000L,
    /** 桥接器状态口 host（TCP 透传时可控风；BLE 透传无 HTTP 通道传 null，风速被禁用） */
    private val fanHost: String? = null,
) : DeviceChannel {

    private val _temperatureFlow = MutableSharedFlow<CurvePoint>(extraBufferCapacity = 64)
    override val temperatureFlow: Flow<CurvePoint> = _temperatureFlow

    private val _svFlow = MutableStateFlow<Float?>(null)
    override val svFlow: StateFlow<Float?> = _svFlow

    private val _eventFlow = MutableSharedFlow<EventMarker>(replay = 0, extraBufferCapacity = 8)
    override val eventFlow: Flow<EventMarker> = _eventFlow

    @Volatile
    override var isConnected: Boolean = false
        private set

    private var startMark: kotlin.time.TimeMark? = null
    private val transactionMutex = Mutex()
    private var job: Job? = null
    private var fanClient: HttpClient? = null   // 懒建：仅在 TCP 透传控风时创建，disconnect 释放

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        transport.open()

        // 连接验证：耐心重试（首次连接时串口子系统可能未就绪）
        var verified = false
        for (i in 1..5) {
            if (transactionMutex.withLock { pollOnce() }) { verified = true; break }
            delay(1200)
        }
        if (!verified) {
            runCatching { transport.close() }
            throw ModbusConnectionException(
                L10n.get("app.s10")
            )
        }

        isConnected = true
        startMark = kotlin.time.TimeSource.Monotonic.markNow()

        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var consecutiveFailures = 0
            while (isActive && isConnected) {
                val cycleStart = kotlin.time.TimeSource.Monotonic.markNow()
                val ok = try {
                    transactionMutex.withLock { pollOnce() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= AUTO_RECONNECT_THRESHOLD) {
                        reconnectTransport()
                        consecutiveFailures = 0
                    }
                }
                val remainMs = pollIntervalMs - cycleStart.elapsedNow().inWholeMilliseconds
                delay(if (remainMs > 50L) remainMs else 50L)
            }
        }
    }

    /** 单次轮询：读 PV+状态字+SV。返回是否成功 */
    private suspend fun pollOnce(): Boolean {
        transport.drain()
        val request = ModbusRtu.buildReadRequest(
            slaveId = slaveId,
            functionCode = ModbusRtu.FUNCTION_READ_HOLDING,
            startAddress = ModbusRtu.Tc4s.PV_ADDRESS,
            quantity = 3,
        )
        transport.write(request)
        val response = transport.readExact(RTU_READ_RESPONSE_LEN_QTY3) ?: return false
        val values = ModbusRtu.parseReadResponse(request, response)
        if (values.size < 3) return false

        val pv = values[0].toFloat()
        val sv = values[2].toFloat()
        val elapsed = elapsedSec()
        _svFlow.value = sv
        _temperatureFlow.emit(CurvePoint(timeSeconds = elapsed, bt = pv))
        return true
    }

    private suspend fun reconnectTransport() {
        try { transport.close() } catch (_: Exception) {}
        try { transport.open() } catch (_: Exception) {}
    }

    /** 重置计时基准（入豆时调用）：后续采样点时间从零开始 */
    override fun resetTimer() { startMark = kotlin.time.TimeSource.Monotonic.markNow() }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        transactionMutex.withLock { /* 拿到锁说明无进行中事务 */ }
        job?.cancel(); job = null
        try { transport.close() } catch (_: Exception) {}
        fanClient?.close(); fanClient = null   // 释放 HTTP 客户端（此前从不 close 泄漏）
    }

    override suspend fun sendCommand(command: DeviceCommand) = withContext(Dispatchers.IO) {
        when (command.type) {
            CommandType.PID_SETPOINT -> {
                val ok = transactionMutex.withLock {
                    writeSingleRegister(ModbusRtu.Tc4s.SV_ADDRESS, command.value.toInt())
                }
                if (!ok) throw ModbusException(L10n.get("app.s11"))
            }
            // 风扇：TCP 透传桥接器有 8898 状态口（与 ModbusTcpChannel 同款 HTTP /fan）；
            // BLE 透传无 HTTP 通道 → 明确报不支持（上层滑块禁用并提示），不再静默抛通用错
            CommandType.FAN_DUTY -> {
                val host = fanHost ?: throw ModbusException("BLE 透传链路不支持风速控制")
                val ok = transactionMutex.withLock {
                    val client = fanClient ?: createFanHttpClient().also { fanClient = it }
                    sendFanSpeed(client, host, command.value.toInt())
                }
                if (!ok) throw ModbusException("风扇写入失败（桥接器无响应）")
            }
            else -> throw ModbusException("command ${command.type} not supported yet")
        }
    }

    private suspend fun writeSingleRegister(address: Int, value: Int): Boolean {
        val request = ModbusRtu.buildWriteSingleRegister(slaveId, address, value)
        transport.write(request)
        val echo = transport.readExact(RTU_WRITE_RESPONSE_LEN) ?: return false
        // FC06 写响应 = 请求回显 [从站,0x06,地址H,地址L,值H,值L,CRC]。
        // 此前借 parseReadResponse（读响应解析）会把地址高字节当 byteCount：
        // SV 地址 0x0002 高字节恰为 0 才碰巧可用，配成 ≥0x0100 即误判（2026-09-06 修复）
        return verifyWriteEcho(request, echo)
    }

    /** 专用写回显校验：CRC + 从站 + 功能码 + 地址 + 值 与请求一致 */
    private fun verifyWriteEcho(request: ByteArray, echo: ByteArray): Boolean {
        if (echo.size < 8) return false
        val crcPos = echo.size - 2
        val expected = ((echo[crcPos + 1].toInt() and 0xFF) shl 8) or (echo[crcPos].toInt() and 0xFF)
        if (ModbusRtu.crc16(echo, 0, crcPos) != expected) return false
        // 请求布局 [从站,0x06,地址H,地址L,值H,值L,CRC]（echo 去掉 CRC 后应逐字节等于请求前 6 字节）
        if (echo.size - 2 != request.size - 2) return false
        for (i in 0 until request.size - 2) {
            if (echo[i] != request[i]) return false
        }
        return true
    }

    override suspend fun sendCharge() { emitLocal(RoastEvent.CHARGE) }
    override suspend fun sendDrop() { emitLocal(RoastEvent.DROP) }
    override suspend fun sendEvent(event: RoastEvent, label: String) { emitLocal(event, label) }

    private suspend fun emitLocal(event: RoastEvent, label: String = "") {
        val elapsed = elapsedSec()
        _eventFlow.emit(EventMarker(event, elapsed, label))
    }

    override fun elapsedSec(): Float =
        (startMark?.elapsedNow()?.inWholeMilliseconds ?: 0L) / 1000f

    companion object {
        /** RTU 读 3 寄存器响应：从站1+功能码1+字节数1+数据6+CRC2 = 11 */
        const val RTU_READ_RESPONSE_LEN_QTY3 = 11
        /** RTU 写单寄存器响应：从站1+功能码1+地址2+值2+CRC2 = 8 */
        const val RTU_WRITE_RESPONSE_LEN = 8
        const val AUTO_RECONNECT_THRESHOLD = 5
    }
}
