package com.roastcurve.shared.protocol

import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent
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
    }

    override suspend fun sendCommand(command: DeviceCommand) = withContext(Dispatchers.IO) {
        when (command.type) {
            CommandType.PID_SETPOINT -> {
                val ok = transactionMutex.withLock {
                    writeSingleRegister(ModbusRtu.Tc4s.SV_ADDRESS, command.value.toInt())
                }
                if (!ok) throw ModbusException(L10n.get("app.s11"))
            }
            else -> throw ModbusException("command ${command.type} not supported yet")
        }
    }

    private suspend fun writeSingleRegister(address: Int, value: Int): Boolean {
        val request = ModbusRtu.buildWriteSingleRegister(slaveId, address, value)
        transport.write(request)
        val echo = transport.readExact(RTU_WRITE_RESPONSE_LEN) ?: return false
        // 借 parseReadResponse 做 CRC + 从站 + 功能码 + 异常码校验（写响应是请求回显）
        ModbusRtu.parseReadResponse(request, echo)
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
