package com.roastcurve.shared.protocol

import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.*

/**
 * Artisan WebSocket JSON 协议实现
 *
 * 兼容 Artisan 的 WebSocket 协议，可以直接连接现有 Artisan 兼容设备：
 * - Croaster (ESP8266/ESP32)
 * - 各种 DIY ESP32 烘焙控制器
 * - 任何实现了 Artisan WebSocket 协议的设备
 *
 * 协议格式：
 * 请求: {"Command": "READ", "MessageID": 1}
 * 响应: {"MessageID": 1, "Data": {"BT": 185.2, "ET": 210.5}}
 * 推送: {"Message": "CHARGE"} / {"Message": "DROP"}
 * 事件: {"Message": "Event", "data": {"Event": "FCs"}}
 */
class ArtisanWebSocketChannel(
    override val name: String,
    private val host: String,
    private val port: Int = 81,
    private val path: String = "/",
    private val btField: String = "BT",
    private val etField: String = "ET",
    private val samplingIntervalMs: Long = 1000L,
) : DeviceChannel {

    private val client = HttpClient {
        install(WebSockets)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var session: WebSocketSession? = null
    private var messageId = 0
    private var job: Job? = null

    private val _temperatureFlow = MutableSharedFlow<CurvePoint>(replay = 0, extraBufferCapacity = 64)
    override val temperatureFlow: Flow<CurvePoint> = _temperatureFlow

    private val _eventFlow = MutableSharedFlow<EventMarker>(replay = 0, extraBufferCapacity = 32)
    override val eventFlow: Flow<EventMarker> = _eventFlow

    @Volatile
    override var isConnected: Boolean = false
        private set

    private var startTime: Long = 0L

    override suspend fun connect() {
        if (isConnected) return

        try {
            session = client.webSocketSession("ws://$host:$port$path")
            isConnected = true
            startTime = nowMillis()

            job = CoroutineScope(Dispatchers.Default).launch {
                while (isActive && isConnected) {
                    try {
                        requestData()
                        delay(samplingIntervalMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 网络错误，继续重试
                        delay(1000)
                    }
                }
            }

            // 启动接收循环
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    session?.let { s ->
                        for (frame in s.incoming) {
                            if (frame is Frame.Text) {
                                handleMessage(frame.readText())
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 连接断开
                    isConnected = false
                }
            }
        } catch (e: Exception) {
            isConnected = false
            throw e
        }
    }

    override suspend fun disconnect() {
        job?.cancel()
        job = null
        try {
            session?.close()
        } catch (_: Exception) {}
        session = null
        isConnected = false
    }

    private suspend fun requestData() {
        val id = ++messageId
        session?.send(
            Frame.Text(
                """{"Command":"READ","MessageID":$id}"""
            )
        )
    }

    private suspend fun handleMessage(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject

            // 处理数据响应
            if (obj.containsKey("Data")) {
                val data = obj["Data"]?.jsonObject ?: return
                val bt = data[btField]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                val et = data[etField]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                val elapsed = (nowMillis() - startTime) / 1000f

                _temperatureFlow.emit(
                    CurvePoint(
                        timeSeconds = elapsed,
                        bt = bt,
                        et = et,
                    )
                )
            }

            // 处理推送消息（CHARGE / DROP）
            if (obj.containsKey("Message")) {
                val message = obj["Message"]?.jsonPrimitive?.content ?: return
                val elapsed = (nowMillis() - startTime) / 1000f
                when (message.uppercase()) {
                    "CHARGE" -> _eventFlow.emit(
                        EventMarker(RoastEvent.CHARGE, elapsed)
                    )
                    "DROP" -> _eventFlow.emit(
                        EventMarker(RoastEvent.DROP, elapsed)
                    )
                    "EVENT" -> {
                        val eventData = obj["data"]?.jsonObject
                        val eventName = eventData?.get("Event")?.jsonPrimitive?.content ?: ""
                        val event = parseEventName(eventName)
                        if (event != null) {
                            _eventFlow.emit(EventMarker(event, elapsed))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略解析错误
        }
    }

    private fun parseEventName(name: String): RoastEvent? = when (name.uppercase()) {
        "DRY" -> RoastEvent.DRY
        "FCS" -> RoastEvent.FCs
        "FCE" -> RoastEvent.FCe
        "SCS" -> RoastEvent.SCs
        "SCE" -> RoastEvent.SCe
        else -> null
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    override suspend fun sendCharge() {
        session?.send(Frame.Text("""{"Message":"CHARGE"}"""))
    }

    override suspend fun sendDrop() {
        session?.send(Frame.Text("""{"Message":"DROP"}"""))
    }

    override suspend fun sendEvent(event: RoastEvent, label: String) {
        val eventName = when (event) {
            RoastEvent.CHARGE -> "CHARGE"
            RoastEvent.DRY -> "DRY"
            RoastEvent.FCs -> "FCs"
            RoastEvent.FCe -> "FCe"
            RoastEvent.SCs -> "SCs"
            RoastEvent.SCe -> "SCe"
            RoastEvent.DROP -> "DROP"
            RoastEvent.CUSTOM -> label
        }
        session?.send(
            Frame.Text("""{"Message":"Event","data":{"Event":"$eventName"}}""")
        )
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        val cmdJson = buildString {
            append("{")
            append("\"Command\":\"${command.type.name}\"")
            append(",\"Value\":${command.value}")
            if (command.target.isNotEmpty()) {
                append(",\"Target\":\"${command.target}\"")
            }
            append("}")
        }
        session?.send(Frame.Text(cmdJson))
    }
}