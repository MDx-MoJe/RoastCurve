package com.roastcurve.shared.protocol

import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * 温控器通道：App --Modbus TCP(8899)--> Wi-Fi 转 RS485 设备 --RS485--> 温控器
 *
 * 可靠性设计：
 * - 所有事务经 Mutex 串行化（轮询与用户写入不交叉）
 * - 读超时用 withTimeoutOrNull（超时≠取消，单次失败跳过本周期）
 * - 连续失败 ≥5 次自动重建 TCP 连接（网络抖动自愈）
 */
class ModbusTcpChannel(
    override val name: String,
    private val host: String,
    private val port: Int = 8899,
    private val slaveId: Int = ModbusTcp.Tc4s.DEFAULT_SLAVE_ID,
    private val pollIntervalMs: Long = 1000L,
) : DeviceChannel {

    private val selector = SelectorManager(Dispatchers.IO)
    private var socket: Socket? = null
    private var input: ByteReadChannel? = null
    private var output: ByteWriteChannel? = null
    private var job: Job? = null

    private val _temperatureFlow = MutableSharedFlow<CurvePoint>(extraBufferCapacity = 64)
    override val temperatureFlow: Flow<CurvePoint> = _temperatureFlow

    private val _svFlow = MutableStateFlow<Float?>(null)
    override val svFlow: StateFlow<Float?> = _svFlow

    private val _eventFlow = MutableSharedFlow<EventMarker>(replay = 0, extraBufferCapacity = 8)
    override val eventFlow: Flow<EventMarker> = _eventFlow

    @Volatile
    override var isConnected: Boolean = false
        private set

    // 单调时钟：不受系统 NTP 校时影响，杜绝计时/RoR 跳动。
    // 必须 @Volatile：此变量被 IO 线程（connect/resetTimer）与 Main 线程（resetTimer、200ms 计时循环）
    // 并发访问，非 volatile 会导致 Main 线程读到过期引用（null/旧 mark），elapsedSec() 短暂跳 0 再跳回，
    // 表现为计时「从 10 跳到不相干的数再回 11/12」（2026-08-30 实锤）。
    @Volatile
    private var startMark: kotlin.time.TimeMark? = null
    private var transId = 0
    private val transactionMutex = Mutex()

    // 风扇 HTTP 客户端：惰性创建，随通道实例存活（同 bridge 复用）
    private var fanClient: HttpClient? = null

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        openTransport()

        // 连接验证：耐心重试（首次连接时串口子系统可能未就绪）
        var verified = false
        for (i in 1..5) {
            if (transactionMutex.withLock { pollOnce() }) { verified = true; break }
            delay(1200)
        }
        if (!verified) {
            runCatching { socket?.close() }
            socket = null; input = null; output = null
            throw ModbusConnectionException(
                L10n.get("app.s12")
            )
        }

        isConnected = true
        startMark = kotlin.time.TimeSource.Monotonic.markNow()

        // 注意：必须用独立 CoroutineScope 启动轮询循环。
        // 若用 withContext 内的 launch，无限循环会让 withContext 永不返回，
        // 导致调用方挂死在 connect() 里（结构化并发陷阱）。
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var consecutiveFailures = 0
            while (isActive && isConnected) {
                // 周期调度：以每轮开始时刻为基准补足剩余等待，
                // 节奏稳定在 pollIntervalMs；查询耗时长时也不叠加额外延迟
                val cycleStart = kotlin.time.TimeSource.Monotonic.markNow()
                // 网络异常按一次失败计，交给连续失败计数走重连；
                // 不接住的话 TCP 异常断开会让协程未捕获异常直接杀进程（2026-08-25 实测崩溃）
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
        val out = output ?: return false
        val inp = input ?: return false
        drainInput(inp)   // 清除上次迟到应答的残留字节，防毒化
        val request = ModbusTcp.buildReadRequest(
            transactionId = nextTransId(),
            slaveId = slaveId,
            functionCode = ModbusTcp.FUNCTION_READ_HOLDING,
            startAddress = 0x0000,
            quantity = 3,
        )
        out.writeFully(request, 0, request.size)
        out.flush()
        val response = readExactOrNull(inp, RESPONSE_LEN_QTY3) ?: return false
        val values = ModbusTcp.parseReadResponse(request, response)
        if (values.size < 3) return false

        val pv = values[0].toFloat()
        val sv = values[2].toFloat()
        val elapsed = elapsedSec()
        _svFlow.value = sv
        _temperatureFlow.emit(CurvePoint(timeSeconds = elapsed, bt = pv))
        return true
    }

    private suspend fun openTransport() {
        socket = aSocket(selector).tcp().connect(host, port) {
            keepAlive = true
            noDelay = true
        }
        input = socket!!.openReadChannel()
        output = socket!!.openWriteChannel(autoFlush = true)
    }

    private suspend fun reconnectTransport() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
        try { openTransport() } catch (_: Exception) {}
    }

    /** 重置计时基准（入豆时调用）：后续采样点时间从零开始 */
    override fun resetTimer() {
        startMark = kotlin.time.TimeSource.Monotonic.markNow()
        println("RESET_TIMER mark=${startMark.hashCode()}")
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        // 先停止新事务的调度
        isConnected = false
        // 等待进行中的事务完成（避免半途关闭污染网关会话状态）
        transactionMutex.withLock { /* 拿到锁说明无进行中事务 */ }
        job?.cancel(); job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    private fun nextTransId(): Int = ++transId and 0xFFFF

    /** 按预期字节数读取；超时/断开返回 null（不抛取消类异常）。可指定超时 */
    private suspend fun readExactOrNull(
        channel: ByteReadChannel,
        count: Int,
        timeoutMs: Long = READ_TIMEOUT_MS,
    ): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            val buffer = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = channel.readAvailable(buffer, filled, count - filled)
                if (n == -1L.toInt()) return@withTimeoutOrNull null
                filled += n
            }
            buffer
        }

    /** 清空接收缓冲区内的残留数据（迟到应答会毒化后续事务） */
    private suspend fun drainInput(channel: ByteReadChannel) {
        val junk = ByteArray(256)
        while (channel.availableForRead > 0) {
            val n = channel.readAvailable(junk, 0, minOf(junk.size, channel.availableForRead))
            if (n == null || n <= 0) break
        }
    }

    override suspend fun sendCommand(command: DeviceCommand) = withContext(Dispatchers.IO) {
        when (command.type) {
            CommandType.PID_SETPOINT -> {
                val ok = transactionMutex.withLock {
                    writeSingleRegister(ModbusTcp.Tc4s.SV_ADDRESS, command.value.toInt())
                }
                if (!ok) throw ModbusException("SV 写入失败（设备无响应）")
            }
            // 风扇占空比：走 RoastBridge 状态口 HTTP（/fan?speed=NN），非 Modbus 寄存器
            // 与轮询串行化避免同时写 TCP 双路；失败抛出由上层（跟随控制器/手动滑块）处理
            CommandType.FAN_DUTY -> {
                val ok = transactionMutex.withLock {
                    sendFanSpeed(fanHttpClient(), host, command.value.toInt())
                }
                if (!ok) throw ModbusException("风扇写入失败（桥接器无响应）")
            }
            else -> throw ModbusException("command ${command.type} not supported yet")
        }
    }

    private fun fanHttpClient(): HttpClient =
        fanClient ?: createFanHttpClient().also { fanClient = it }

    private suspend fun writeSingleRegister(address: Int, value: Int): Boolean {
        // 低波特率下自动收发模块偶发喳帧，重试能吞毛刺。
        // 但重试间隔递增且总次数克制，避免长时间持锁阻塞轮询（轮询停摆会让曲线/模板时钟跳变）。
        // 更重的重试交给上层（跟随控制器 2 秒一拍、手动 ±5 由用户再点），不在此层层层加码。
        repeat(2) { attempt ->
            if (attempt > 0) delay(300L)   // 一次静默后重试
            if (writeSingleRegisterOnce(address, value)) return true
        }
        return false
    }

    private suspend fun writeSingleRegisterOnce(address: Int, value: Int): Boolean {
        val out = output ?: return false
        val inp = input ?: return false
        // 先清掉残留字节（上次迟到应答会毒化本次回显解析），与轮询同策略
        drainInput(inp)
        val request = ModbusTcp.buildWriteSingleRegister(nextTransId(), slaveId, address, value)
        out.writeFully(request, 0, request.size)
        out.flush()
        // 写用独立短超时：1200 波特率写响应约 350-470ms，1.5s 足够；
        // 避免用读的 2.5s 超时，减少写失败时对轮询的长时间阻塞（这是「跳秒」的诱因之一）
        val echo = readExactOrNull(inp, WRITE_RESPONSE_LEN, WRITE_TIMEOUT_MS) ?: return false
        // 用写响应专用校验（回显地址/值一致），不再误用读响应解析器
        return ModbusTcp.verifyWriteResponse(request, echo)
    }

    override suspend fun sendCharge() { emitLocal(RoastEvent.CHARGE) }
    override suspend fun sendDrop() { emitLocal(RoastEvent.DROP) }
    override suspend fun sendEvent(event: RoastEvent, label: String) { emitLocal(event, label) }

    private suspend fun emitLocal(event: RoastEvent, label: String = "") {
        val elapsed = elapsedSec()
        _eventFlow.emit(EventMarker(event, elapsed, label))
    }

    override fun elapsedSec(): Float {
        val mark = startMark ?: return 0f
        return mark.elapsedNow().inWholeMilliseconds / 1000f
    }

    companion object {
        const val READ_TIMEOUT_MS = 2500L
        const val WRITE_TIMEOUT_MS = 1500L   // 写响应短超时，避免写失败长时间阻塞轮询
        const val RESPONSE_LEN_QTY3 = 15
        const val WRITE_RESPONSE_LEN = 12
        const val AUTO_RECONNECT_THRESHOLD = 5
    }
}