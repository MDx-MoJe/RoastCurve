package com.roastcurve.app.monitor

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roastcurve.app.chart.ChartData
import com.roastcurve.app.chart.ChartViewport
import com.roastcurve.app.chart.RoastChart
import com.roastcurve.design.DarkRoast
import com.roastcurve.design.WarmBeige
import com.roastcurve.app.util.toFixed1
import com.roastcurve.shared.math.RoastMath
import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent
import com.roastcurve.shared.protocol.CommandType
import com.roastcurve.shared.protocol.DeviceChannel
import com.roastcurve.shared.protocol.DeviceCommand
import com.roastcurve.shared.protocol.ModbusTcpChannel
import com.roastcurve.shared.protocol.SignalProbe
import com.roastcurve.shared.protocol.TcpByteTransport
import com.roastcurve.shared.protocol.TransparentChannel
import com.roastcurve.shared.protocol.createBleTransport
import com.roastcurve.shared.storage.ProfileStore
import com.roastcurve.app.platform.keepAliveStart
import com.roastcurve.app.platform.keepAliveStop
import com.roastcurve.shared.storage.RoastStore
import com.roastcurve.shared.storage.SettingsStore
import com.roastcurve.shared.model.Settings
import com.roastcurve.shared.model.RoastProfile
import com.roastcurve.shared.model.LinkType
import com.roastcurve.shared.model.RoastRecord
import com.roastcurve.shared.model.SessionState
import com.roastcurve.shared.storage.SessionStore
import com.roastcurve.shared.bridge.BeanBagBridge
import com.roastcurve.shared.bridge.BridgeResult
import com.roastcurve.shared.bridge.GreenBeanSummary
import com.roastcurve.shared.bridge.beanBagBridge
import com.roastcurve.shared.bridge.isBridgeAvailableOnPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ===== RoR 自适应风速（自动风速模式）参数，实机调参 =====
private const val FAN_KP = 5.0f        // RoR 偏差→风速修正增益（%/每°C/min）
private const val FAN_DEADZONE = 3.0f  // 死区：|偏差|<3°C/min 不修正（防噪声触发）
private const val FAN_LIMIT = 10.0f    // 修正量限幅 ±10%（防振荡）
private const val FAN_RAMP = 5.0f      // 风速每拍最大变化 ±5%（执行器速率限制，治振荡关键）
private const val FAN_MIN_PCT = 7f     // 风速下限（与固件 FAN_DUTY_FLOOR 对齐）
private const val FAN_MAX_PCT = 99f    // 风速上限（与固件 FAN_DUTY_CEIL 对齐）

/**
 * 实时监控面板
 *
 * 数据源：真机 Modbus TCP 通道（Wi-Fi 转 RS485 设备 → 温控器）。
 */

/** 图表纵轴动态下限：室温起步时曲线可见；数据升高后回到标准 50° 起点 */
internal fun dynTempMin(curve: List<com.roastcurve.shared.model.CurvePoint>): Float {
    val minBt = curve.minOfOrNull { it.bt } ?: return 50f
    if (minBt >= 55f) return 50f
    return (((minBt - 5f) / 25f).toInt() * 25f).coerceAtLeast(0f)
}

@Composable
fun MonitorScreen(
    settings: Settings = Settings(),
    hostRefreshKey: Int = 0,
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCreateProfile: () -> Unit = {},
    onEditProfile: (RoastProfile) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val curvePoints = remember { mutableStateListOf<CurvePoint>() }
    val events = remember { mutableStateListOf<EventMarker>() }
    var startTimeSec by remember { mutableStateOf(0f) }
    // 恢复会话的时间轴偏移：进程被杀重连后设备 timer 从 0 起，加此偏移保持曲线时间连续
    var timerOffsetSec by remember { mutableStateOf(0f) }

    // ===== 真实设备连接状态 =====
    // hostInput 初始为空占位，实际值从持久化设置读（记住上次用的 IP）
    var hostInput by remember { mutableStateOf("") }
    var linkType by remember { mutableStateOf(LinkType.MODBUS_TCP) }
    var bleAddress by remember { mutableStateOf("") }
    var useRealDevice by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    // 错误提示自动消失：避免「设定失败/连接失败」一直挂着，让人误以为持续有问题
    LaunchedEffect(connectionError) {
        if (connectionError != null) {
            delay(6000)
            connectionError = null
        }
    }
    var channel by remember { mutableStateOf<DeviceChannel?>(null) }
    var currentSv by remember { mutableStateOf<Float?>(null) }
    var writingSv by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var collectJob by remember { mutableStateOf<Job?>(null) }
    var connecting by remember { mutableStateOf(false) }   // connect 幂等门控：防止并发 connect 产生双连接
    // 本炉记录 id：草稿/定稿共用，自动落盘按它覆盖更新，不重复建档
    var sessionId by remember { mutableStateOf<String?>(null) }
    var persistBusy by remember { mutableStateOf(false) }
    // 会话记录门控：true=采集中写曲线；false=待命只看实时温度
    var recording by remember { mutableStateOf(false) }
    var liveBt by remember { mutableStateOf<Float?>(null) }
    var bridgeRssi by remember { mutableStateOf<Int?>(null) }   // WiFi 信号强度（dBm），来自桥接器状态口
    // 自然时间计时显示：独立于采样节奏，按真实秒连续走动
    var displayTimeSec by remember { mutableStateOf(0f) }

    // ===== 跟随模式（自动烘焙）状态 =====
    var followMode by remember { mutableStateOf(false) }
    var activeProfile by remember { mutableStateOf<RoastProfile?>(null) }
    var showProfilePicker by remember { mutableStateOf(false) }
    var followAlert by remember { mutableStateOf<String?>(null) }
    val svHistory = remember { mutableListOf<Float>() }        // SV 平滑窗口（Artisan 同款 5 点）
    var hardBadSince by remember { mutableStateOf(0) }        // PV≥250°C 持续计数（硬上限保护，拍数×2s）
    var followClock by remember { mutableStateOf(0f) }         // 诊断：模板时钟
    var followTarget by remember { mutableStateOf<Float?>(null) }   // 诊断：当前目标温度
    var followLastSv by remember { mutableStateOf<Float?>(null) }   // 诊断：最近回读的 SV

    // ===== 风速控制状态（双变量烘焙：手动滑块 + 跟随曲线）=====
    var fanSpeed by remember { mutableStateOf(0f) }           // 当前风速 0-100%
    var fanLastSent by remember { mutableStateOf<Float?>(null) }   // 去重：最近已下发风速
    var followFanTarget by remember { mutableStateOf<Float?>(null) } // 诊断：跟随中的风速目标
    var fanSending by remember { mutableStateOf(false) }      // 发送门控（防止连发/并发）
    var fanManualOverride by remember { mutableStateOf(false) } // 手动覆盖风速曲线（双变量解耦：只停风速跟随，温度曲线照跟）
    var fanAuto by remember { mutableStateOf(false) }        // 自动风速（RoR 自适应）：风速 = 曲线基准 + RoR 偏差修正

    // ===== 豆袋互联状态 =====
    var showBeanBagSync by remember { mutableStateOf(false) }     // 出豆后弹同步扣库存对话框
    var pendingRoastId by remember { mutableStateOf<String?>(null) } // 本炉幂等键（入豆时生成，出豆时用）

    /** 直接写 SV（跟随模式用；adjustSv 是相对量） */
    fun setSvAbsolute(target: Float) {
        val ch = channel ?: return
        if (writingSv) return
        writingSv = true
        scope.launch(Dispatchers.Main) {
            try {
                ch.sendCommand(DeviceCommand(CommandType.PID_SETPOINT, target))
            } catch (e: Exception) {
                connectionError = L10n.get("monitor.s1", "message" to (e.message ?: ""))
            } finally {
                writingSv = false
            }
        }
    }

    /** 设置风速 0-100%（跟随模式与手动滑块共用；走桥接器 HTTP /fan）
     *  与 setSvAbsolute 同语义：写入失败不打断会话，仅提示 */
    fun setFanSpeed(value: Float) {
        val ch = channel ?: return
        if (fanSending) return
        fanSending = true
        val v = value.coerceIn(0f, 100f)
        scope.launch(Dispatchers.Main) {
            try {
                ch.sendCommand(DeviceCommand(CommandType.FAN_DUTY, v))
                fanSpeed = v
                fanLastSent = v
            } catch (e: Exception) {
                connectionError = L10n.get("monitor.fan_err", "message" to (e.message ?: ""))
            } finally {
                fanSending = false
            }
        }
    }

    fun exitFollow(alert: String? = null) {
        followMode = false
        followAlert = alert
    }

    /** 把「记录中」运行态快照持久化，进程被杀后据此恢复 */
    fun saveSessionState() {
        if (!recording) return
        val st = SessionState(
            recording = recording,
            sessionId = sessionId,
            followMode = followMode,
            activeProfileId = activeProfile?.id,
            startTimeSec = startTimeSec,
            savedAtEpoch = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
        scope.launch { SessionStore().save(st) }
    }

    /** 会话结束（定稿/断开）后清掉运行态快照 */
    fun clearSessionState() {
        scope.launch { SessionStore().clear() }
    }

    /** 自动落盘：≥30 个采样点才写；同一炉固定 sessionId 覆盖更新，历史里始终只有一条 */
    fun persistSession() {
        // 防御：非记录态一律不落盘。停止/断开后 curvePoints 仍残留，
        // 若不加这个守卫，后台草稿循环会拿新 id 写出重复记录
        if (!recording) return
        if (persistBusy || curvePoints.size < 30) return
        val id = sessionId
            ?: RoastStore.newId(kotlinx.datetime.Clock.System.now().toEpochMilliseconds()).also { sessionId = it }
        val snapshot = curvePoints.toList()
        val evs = events.toList()
        val total = startTimeSec
        persistBusy = true
        scope.launch {
            try {
                RoastStore().save(
                    RoastRecord(
                        id = id,
                        totalTimeSeconds = total,
                        curveData = snapshot,
                        events = evs,
                    )
                )
            } catch (_: Exception) {
            } finally { persistBusy = false }
        }
        saveSessionState()   // 草稿落盘同步刷新运行态快照（进程被杀恢复用）
    }

    /** 只结束记录不定连接：本炉定稿归档，回到待命态（可继续看温度/再开一炉） */
    fun stopRecording() {
        if (!recording) return
        // 停止记录同时退出跟随：否则跟随控制器（followMode 仍 true）会继续每 2 秒改 SV，
        // 表现为「点停止不停止」（2026-08-30 实锤，出豆后 SV 被改回高温同源）
        followMode = false
        followAlert = null
        persistSession()
        recording = false
        sessionId = null
        clearSessionState()
    }

    fun disconnect() {
        collectJob?.cancel(); collectJob = null
        followMode = false
        followAlert = null
        if (recording) {
            // 记录中直接断开：兜底定稿
            persistSession()
            recording = false
            sessionId = null
            displayTimeSec = 0f
        }
        scope.launch { channel?.disconnect() }
        channel = null
        useRealDevice = false
        currentSv = null
        keepAliveStop()   // 会话结束，撤掉前台服务与锁
        clearSessionState()
    }

    // 连接期间每 30 秒自动落盘草稿：崩溃/意外断连最多丢 30 秒数据
    LaunchedEffect(useRealDevice) {
        while (useRealDevice && recording) {   // 只在记录中循环，停止后立即停转，不再空转写新档
            delay(30_000)
            persistSession()
        }
    }

    // 信号强度轮询：连上后每 5 秒读一次桥接器状态口（不阻塞主循环）
    LaunchedEffect(useRealDevice, hostInput) {
        if (!useRealDevice) return@LaunchedEffect
        while (useRealDevice) {
            val h = hostInput.trim()
            if (h.isNotEmpty() && linkType == LinkType.MODBUS_TCP) {
                bridgeRssi = SignalProbe.fetchRssi(h)
            }
            delay(5000)
        }
    }

    /** 调整设定温度（写入温控器） */
    fun adjustSv(delta: Float) {
        val base = currentSv ?: return
        val ch = channel ?: return
        if (writingSv) return
        val target = (base + delta).coerceIn(0f, 400f)
        writingSv = true
        scope.launch(Dispatchers.Main) {
            try {
                ch.sendCommand(DeviceCommand(CommandType.PID_SETPOINT, target))
                // 写入成功，下一轮询周期回读刷新 currentSv
            } catch (e: Exception) {
                connectionError = L10n.get("monitor.s1", "message" to (e.message ?: ""))
            } finally {
                writingSv = false
            }
        }
    }

    /** 导入 Artisan .alog：注册文件回调 → 解析 → 存为新模板 */
    fun startArtisanImport() {
        com.roastcurve.shared.BackupBridge.onPicked = { data, nameHint ->
            com.roastcurve.shared.BackupBridge.onPicked = null   // 一次性
            if (data != null) {
                scope.launch(Dispatchers.Main) {
                    try {
                        val text = data.decodeToString()
                        val curve = com.roastcurve.shared.io.ArtisanAlog.parseBt(text)
                            ?: run { followAlert = L10n.get("monitor.s2"); return@launch }
                        val pts = com.roastcurve.shared.io.ArtisanAlog.toPoints(curve)
                        val computedEvents = com.roastcurve.shared.io.ArtisanAlog.parseComputedEvents(text)
                        val anchors = com.roastcurve.shared.io.ArtisanAlog.deriveAnchors(pts, curve.phases, computedEvents)
                        val nm = nameHint?.removeSuffix(".alog")?.removeSuffix(".ALOG")
                            ?.ifBlank { null }
                            ?: curve.title ?: L10n.get("monitor.s3")
                        // 同名同源的旧模板覆盖更新（重导不重复堆积）
                        val existId = ProfileStore().listAll()
                            .find { it.sourceRecordId == "artisan-alog" && it.name == nm }?.id
                        ProfileStore().save(
                            RoastProfile(
                                id = existId ?: RoastStore.newId(kotlinx.datetime.Clock.System.now().toEpochMilliseconds()),
                                name = nm,
                                sourceRecordId = "artisan-alog",
                                points = pts,
                                anchors = anchors,
                            )
                        )
                        followAlert = if (computedEvents.isNotEmpty())
                            L10n.get("monitor.s4", "nm" to nm, "size" to pts.size, "size2" to computedEvents.size)
                        else
                            L10n.get("monitor.s5", "nm" to nm, "size" to pts.size)
                        activeProfile = ProfileStore().listAll().find { it.id.startsWith("2026") && it.name == nm } ?: activeProfile
                        showProfilePicker = true   // 重开选择器展示结果
                    } catch (e: Exception) {
                        followAlert = L10n.get("monitor.import_failed", "msg" to (e.message?.take(40) ?: ""))
                    }
                }
            }
        }
        com.roastcurve.shared.BackupBridge.requestPick?.invoke()
    }

    fun connect(host: String) {
        connectionError = null
        println("CONNECT called host=$host connecting=$connecting oldChannel=${channel != null} oldCollectJob=${collectJob != null}")
        // 幂等门控：connect 非原子（ch.connect() 是异步 suspend），快速双击或自动+手动叠加
        // 会并发进入两次。第一次完成后 channel 已就绪，第二次若还进来直接忽略（已有活跃连接）。
        if (connecting) { println("CONNECT ignored: already connecting"); return }
        if (channel != null && (channel?.isConnected == true)) {
            println("CONNECT ignored: already connected")
            return
        }
        connecting = true
        // 清理旧连接（残留的未连接 channel 或已断开引用）
        collectJob?.cancel(); collectJob = null
        val oldChannel = channel
        channel = null
        if (oldChannel != null) {
            scope.launch(Dispatchers.IO) { runCatching { oldChannel.disconnect() } }
        }
        scope.launch(Dispatchers.Main) {
            try {
                var lastError: Exception? = null
                var ch: DeviceChannel? = null
                // 重试 3 次（首次失败常是网络栈瞬态，间隔递增）
                for (attempt in 1..3) {
                    try {
                        ch = when (linkType) {
                            LinkType.MODBUS_TCP -> ModbusTcpChannel(
                                name = L10n.get("monitor.s6"), host = host,
                                slaveId = settings.modbusSlaveId,
                                pvRegister = settings.modbusPvReg,
                                svRegister = settings.modbusSvReg,
                            )
                            LinkType.TCP_TRANSPARENT -> TransparentChannel(
                                name = L10n.get("monitor.s6"),
                                transport = TcpByteTransport(host = host, port = 8899),
                            )
                            LinkType.BLE_TRANSPARENT -> TransparentChannel(
                                name = L10n.get("monitor.s6"),
                                transport = createBleTransport(bleAddress.trim()),
                            )
                        }
                        ch.connect()
                        lastError = null
                        break
                    } catch (e: Exception) {
                        lastError = e
                        ch = null
                        delay(1200L * attempt)
                    }
                }
                if (ch == null || lastError != null) throw lastError!!
                channel = ch
                // 记住这次成功连接的 IP，下次打开直接填好
                if (linkType != LinkType.BLE_TRANSPARENT && host.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val s = SettingsStore()
                            s.save(s.load().copy(lastBridgeHost = host))
                        }
                    }
                }
                if (!recording) {
                    // 新会话：从头记录
                    curvePoints.clear()
                    events.clear()
                    startTimeSec = 0f
                    timerOffsetSec = 0f
                    displayTimeSec = 0f
                } else {
                    // 恢复的会话：保留已恢复曲线，重连后继续追加，timer 偏移保持时间连续
                    timerOffsetSec = startTimeSec
                }
                useRealDevice = true
                keepAliveStart()   // 前台服务保活：锁屏/切后台不被杀
                // recording 保持原值：新会话=false 待命；恢复会话=true 继续记录
                if (!recording) displayTimeSec = 0f
                collectJob = scope.launch(Dispatchers.Main) {
                    launch {
                        ch.temperatureFlow.collect { point ->
                            liveBt = point.bt
                            if (recording) {
                                val shifted = point.copy(
                                    timeSeconds = point.timeSeconds + timerOffsetSec,
                                    fanDuty = fanSpeed,   // 记录当前风速（双变量曲线数据）
                                )
                                curvePoints.add(shifted)
                                startTimeSec = shifted.timeSeconds
                            }
                        }
                    }
                    launch {
                        ch.svFlow.collect { currentSv = it }
                    }
                    launch {
                        while (isActive) {
                            // 仅记录中刷新自然时钟；待命态保持 0:00
                            println("RC_TICK rec=$recording ch=${ch.elapsedSec()} disp=$displayTimeSec off=$timerOffsetSec start=$startTimeSec")
                            if (recording) displayTimeSec = ch.elapsedSec() + timerOffsetSec
                            delay(200)
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                    connectionError = when {
                        L10n.get("monitor.s7") in msg || "Artisan" in msg -> msg
                        // 瞬时网络错误：3 次重试后仍失败多半是设备不在线，不要照搬系统层原文误导用户
                        "route to host" in msg || "EHOSTUNREACH" in msg || "ENETUNREACH" in msg ->
                            L10n.get("monitor.s8")
                        else -> L10n.get("monitor.s9", "simpleName" to e.javaClass.simpleName)
                    }
            } finally {
                connecting = false
            }
        }
    }

    // 合成带 RoR 的完整曲线（与历史回看共用同一公式）
    val fullCurve = remember(curvePoints.size) {
        RoastMath.withRor(curvePoints.toList())
    }

    // ===== 跟随模式控制器：每 2 秒一拍，Artisan followBackground 同款策略 =====
    // 时间轴锚定入豆事件：入豆前目标冻结在模板起点，按下入豆时钟才开跑
    // 注意：循环内必须从 curvePoints（SnapshotStateList）实时读最新点，
    // 不能引用 fullCurve —— 那是组合时的快照，会让协程拿旧数据冻结控制
    LaunchedEffect(followMode, activeProfile, useRealDevice) {
        var rorEma = 0f   // 实时 RoR 的 EMA 平滑（自动风速用），effect 作用域内持续
        while (followMode && useRealDevice && activeProfile != null) {
            delay(2000)
            val profile = activeProfile ?: break
            val ch = channel ?: break
            // 时间轴改用设备自然计时（单调时钟），不再用 lastP.timeSeconds：
            // 写 SV 阻塞轮询时采样点停更，若用 lastP 会让模板时钟/目标值卡顿甚至跳变（「跳秒」）。
            // elapsedSec() 与采样点、入豆事件共用同一单调时钟基准，可直接替换。
            val t = ch.elapsedSec()
            val lastP = curvePoints.lastOrNull()
            // 模板时钟：入豆后经过的时间（未入豆则为 0，目标停在起点）
            val chargeT = events.find { it.event == RoastEvent.CHARGE }?.timeSeconds
            val tEff = if (chargeT != null) (t - chargeT).coerceAtLeast(0f) else 0f
            followClock = tEff

            // 目标值：带前瞻时取 tEff+N 的目标提前预热；超出末段则保持当前目标，
            // 直到实际时间也走完才退出跟随
            val laSec = settings.lookaheadSec.toFloat()
            val rawAtT = RoastMath.profileTargetAt(profile.points, tEff)
            val rawLa = if (laSec > 0f) RoastMath.profileTargetAt(profile.points, tEff + laSec) else null
            val raw = rawLa ?: rawAtT
            val bt = lastP?.bt
            if (raw == null || rawAtT == null) {
                // 曲线走完：回落设定到用户配置的结束值（默认 25°C/25%），不再停在终点干烧
                // 直发绕过 writingSv 去重门控（跟随循环已退出，无并发写；确保回落必达）
                val chSettle = channel
                if (chSettle != null) {
                    scope.launch {
                        try {
                            chSettle.sendCommand(DeviceCommand(CommandType.PID_SETPOINT, settings.followEndSv.toFloat()))
                        } catch (_: Exception) {}
                        try {
                            chSettle.sendCommand(DeviceCommand(CommandType.FAN_DUTY, settings.followEndFan.toFloat()))
                        } catch (_: Exception) {}
                        fanSpeed = settings.followEndFan.toFloat()
                    }
                }
                followAlert = L10n.get("monitor.s88", "sv" to settings.followEndSv.toString())
                followMode = false
                break
            }
            // PV 硬上限保护（与固件对齐）：实测温度 ≥250°C 持续 5s → 回落退出
            // 防探头损坏/加热失控把炉子带飞；正常烘焙 PV 不会到这（跟随无人值守才拦）
            if (bt != null && bt >= 250f) {
                hardBadSince += 2
                if (hardBadSince >= 5) {
                    val chHard = channel
                    if (chHard != null) {
                        scope.launch {
                            try {
                                chHard.sendCommand(DeviceCommand(CommandType.PID_SETPOINT, settings.followEndSv.toFloat()))
                            } catch (_: Exception) {}
                            try {
                                chHard.sendCommand(DeviceCommand(CommandType.FAN_DUTY, settings.followEndFan.toFloat()))
                            } catch (_: Exception) {}
                            fanSpeed = settings.followEndFan.toFloat()
                        }
                    }
                    followAlert = L10n.get("monitor.s89")
                    followMode = false
                    break
                }
            } else {
                hardBadSince = 0
            }
            followTarget = raw
            // Artisan 同款五点衰减加权平滑，抹平台阶
            svHistory.add(raw)
            if (svHistory.size > 5) svHistory.removeAt(0)
            val target = RoastMath.smoothSv(svHistory).coerceIn(0f, 260f)

            // 变化 ≥1° 才写寄存器（同 Artisan 去重写入）
            val cur = currentSv
            followLastSv = cur
            if (cur != null && kotlin.math.abs(target - cur) >= 1f && !writingSv) {
                setSvAbsolute(target)
            }

            // 风速曲线跟随：模板带风速锚点才动；变化 ≥1% 才写（同 SV 去重）
            // 双变量解耦：手动覆盖风速（fanManualOverride）只暂停风速曲线下发，温度曲线照常跟
            // 自动风速（fanAuto）：风速 = 曲线基准 + RoR 偏差修正（当前 RoR 偏离目标 RoR 时调风拉回）
            val fanBase = if (profile.fanAnchors.isNotEmpty())
                RoastMath.fanTargetAt(profile.fanAnchors, tEff)
            else null
            var targetFan = fanBase
            if (fanAuto && fanBase != null && !fanManualOverride) {
                val targetRor = RoastMath.profileRoRAt(profile.points, tEff)
                // 30 秒窗口 + 重度 EMA（α=0.15）：空锅/实豆 RoR 都是噪声大户，必须当趋势跟踪而非瞬时值
                val rawRor = RoastMath.lastRoR(curvePoints.toList(), 30f)
                if (targetRor != null && rawRor != null) {
                    rorEma = if (rorEma == 0f) rawRor else rorEma * 0.85f + rawRor * 0.15f
                    val e = rorEma - targetRor
                    if (kotlin.math.abs(e) > FAN_DEADZONE) {
                        val delta = (FAN_KP * e).coerceIn(-FAN_LIMIT, FAN_LIMIT)
                        targetFan = (fanBase + delta).coerceIn(FAN_MIN_PCT, FAN_MAX_PCT)
                        // 变化率限制：每拍最多走 ±FAN_RAMP（执行器速率限制，防极限振荡）
                        val last = fanLastSent
                        if (last != null) {
                            targetFan = targetFan.coerceIn(last - FAN_RAMP, last + FAN_RAMP)
                        }
                    }
                    // 自动风速工艺下限：低于流化阈值的豆子不翻滚会积热局部焦化，
                    // 且豆堆静止时 RoR 读数失真（反馈失效）。放在斜率限制之后，
                    // 下限抬升不受每拍限速拖慢（安全优先于平滑）。
                    targetFan = targetFan.coerceAtLeast(settings.fanAutoFloorPct.toFloat())
                }
            }
            followFanTarget = targetFan
            if (targetFan != null && !fanManualOverride && !fanSending && targetFan != fanLastSent) {
                setFanSpeed(targetFan)
            }
        }
    }

    // 启动恢复：检测上次进程被杀遗留的「记录中」会话，恢复曲线与跟随状态
    var sessionRestored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (sessionRestored) return@LaunchedEffect
        val st = SessionStore().load()
        val sid = st.sessionId
        if (st.recording && sid != null) {
            sessionRestored = true
            recording = true
            sessionId = sid
            followMode = st.followMode
            startTimeSec = st.startTimeSec
            timerOffsetSec = st.startTimeSec
            displayTimeSec = st.startTimeSec
            RoastStore().load(sid)?.let { r ->
                curvePoints.clear()
                curvePoints.addAll(r.curveData)
                events.clear()
                events.addAll(r.events)
            }
            val pid = st.activeProfileId
            if (pid != null) {
                activeProfile = ProfileStore().listAll().find { it.id == pid }
            }
            followAlert = L10n.get("monitor.s12")
        }
    }

    // 启动自动连接（设置里可开关）：进程内仅尝试一次。
    // 直接读持久化配置而非 props——props 可能尚未异步加载完（启动竞态）
    var autoConnectDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoConnectDone) {
            autoConnectDone = true
            val stored = SettingsStore().load()
            // 记住上次用的 IP：有存档用存档，没有才落到一个指引性的默认（不写死具体设备）
            if (hostInput.isEmpty()) hostInput = stored.lastBridgeHost.ifEmpty { "" }
            if (stored.autoConnectOnLaunch && stored.lastBridgeHost.isNotEmpty()) {
                delay(2000)
                connect(hostInput.trim())
            }
        }
    }

    // 配网完成返回后重新读 IP（hostRefreshKey 递增触发）：自动填写新桥接器 IP
    LaunchedEffect(hostRefreshKey) {
        if (hostRefreshKey > 0) {
            val stored = SettingsStore().load()
            val ip = stored.lastBridgeHost
            if (ip.isNotEmpty()) {
                hostInput = ip
                // 若开启了自动连接，直接尝试连新 IP
                if (stored.autoConnectOnLaunch) {
                    delay(1000)
                    connect(ip)
                }
            }
        }
    }


    // —— 区块组件：单列与双栏布局共用（闭包捕获本作用域状态）——

    val SectionHeader: @Composable () -> Unit = {
// ===== 标题行 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L10n.get("monitor.s13"), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onOpenHistory,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(L10n.get("monitor.s14"), maxLines = 1, fontSize = 13.sp) }
                OutlinedButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(L10n.get("common.settings"), maxLines = 1, fontSize = 13.sp) }
                // 已连接未记录：手动起表；采集中：停止=断开并定稿保存
                if (useRealDevice && !recording) {
                    Button(onClick = {
                        channel?.resetTimer()
                        curvePoints.clear()
                        events.clear()
                        startTimeSec = 0f
                        displayTimeSec = 0f
                        recording = true
                        saveSessionState()
                    }) { Text(L10n.get("monitor.s15")) }
                }
                if (useRealDevice && recording) {
                    Button(onClick = { stopRecording() }) { Text(L10n.get("monitor.s16")) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

            }

    val SectionConnection: @Composable () -> Unit = {
// ===== 设备连接区 =====
        // 链路类型选择
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                LinkType.MODBUS_TCP to "Modbus TCP",
                LinkType.TCP_TRANSPARENT to L10n.get("monitor.s17"),
                LinkType.BLE_TRANSPARENT to L10n.get("monitor.s18"),
            ).forEach { (t, label) ->
                FilterChip(
                    selected = linkType == t,
                    onClick = { linkType = t },
                    label = { Text(label) },
                    enabled = !useRealDevice,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = if (linkType == LinkType.BLE_TRANSPARENT) bleAddress else hostInput,
                onValueChange = { v ->
                    if (linkType == LinkType.BLE_TRANSPARENT) bleAddress = v else hostInput = v
                },
                label = { Text(if (linkType == LinkType.BLE_TRANSPARENT) L10n.get("monitor.s19") else L10n.get("monitor.s20")) },
                enabled = !useRealDevice,
                singleLine = true,
                modifier = Modifier.weight(1f).height(56.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    val addr = if (linkType == LinkType.BLE_TRANSPARENT) bleAddress.trim() else hostInput.trim()
                    if (useRealDevice) disconnect() else connect(addr)
                },
            ) {
                Text(if (useRealDevice) L10n.get("monitor.s21") else L10n.get("monitor.s22"))
            }
        }
        // 动画显隐：淡入淡出+高度展开/收起，避免「弹出/顶下去」的布局跳动（计时闪烁的根治）
        AnimatedVisibility(
            visible = connectionError != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    connectionError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { connectionError = null },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text(L10n.get("monitor.s23"), style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (useRealDevice) {
            Text(
                L10n.get("monitor.s24"),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
            // 信号强度指示：绿/黄/红三档 + 具体 dBm
            bridgeRssi?.let { rssi ->
                Spacer(Modifier.width(8.dp))
                val (sigColor, sigLabel) = when {
                    rssi >= -60 -> MaterialTheme.colorScheme.primary to L10n.get("monitor.s25")
                    rssi >= -70 -> Color(0xFFC08A00) to L10n.get("monitor.s26")
                    rssi >= -80 -> Color(0xFFC05A2E) to L10n.get("monitor.s27")
                    else -> MaterialTheme.colorScheme.error to L10n.get("monitor.s28")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(sigColor)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        L10n.get("monitor.s29", "sigLabel" to sigLabel, "rssi" to rssi),
                        color = sigColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

            }

    val SectionModeCard: @Composable () -> Unit = {
// ===== 烘焙模式卡（手动 / 跟随曲线，仅真机模式）=====
        if (useRealDevice) {
            Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(L10n.get("monitor.s30"), style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = !followMode,
                                onClick = { if (followMode) exitFollow() },
                                label = { Text(L10n.get("monitor.s31")) },
                            )
                            FilterChip(
                                selected = followMode,
                                onClick = {
                                    when {
                                        followMode -> exitFollow()
                                        activeProfile == null -> showProfilePicker = true
                                        else -> { svHistory.clear(); hardBadSince = 0; fanManualOverride = false; followMode = true; followAlert = null }
                                    }
                                },
                                label = { Text(L10n.get("monitor.s32")) },
                            )
                        }
                    }

                    val prof = activeProfile
                    if (prof != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                L10n.get("monitor.s33", "name" to prof.name),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            TextButton(onClick = { showProfilePicker = true }) {
                                Text(L10n.get("monitor.s34"), style = MaterialTheme.typography.labelSmall)
                            }
                            // 实时偏差（跟随中显示数字，手动时提示参照）
                            val dev = fullCurve.lastOrNull()?.bt?.let { bt ->
                                RoastMath.profileTargetAt(prof.points, fullCurve.lastOrNull()?.timeSeconds ?: 0f)?.let { bt - it }
                            }
                            Text(
                                when {
                                    !followMode -> L10n.get("monitor.s35")
                                    dev != null -> "Δ ${if (dev >= 0) "+" else ""}${dev.toFixed1()}°"
                                    else -> "Δ --"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    !followMode || dev == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    kotlin.math.abs(dev) <= 5f -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(L10n.get("monitor.s36"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (followMode) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            L10n.get("monitor.diag", "tgt" to (followTarget?.toInt()?.toString() ?: "--"), "sv" to (followLastSv?.toInt()?.toString() ?: "--"), "clk" to followClock.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            followAlert?.let { alert ->
                Spacer(Modifier.height(6.dp))
                // 告警横幅：醒目容器色 + 图标 + 可关闭
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("⚠️ ", fontWeight = FontWeight.Bold)
                        Text(
                            alert,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { followAlert = null }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(L10n.get("monitor.s23"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

            }

    val SectionSvControl: @Composable () -> Unit = {
// ===== SV 设定温度控制条（仅真机模式）=====
        if (useRealDevice) {
            Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(L10n.get("monitor.s37"), style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            currentSv?.let { "${it.toInt()}°C" } ?: "--",
                            fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                // 手动介入：跟随模式立即退出
                                if (followMode) exitFollow(L10n.get("monitor.s38"))
                                adjustSv(-5f)
                            },
                            enabled = !writingSv && currentSv != null,
                        ) { Text("-5", fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = {
                                if (followMode) exitFollow(L10n.get("monitor.s38"))
                                adjustSv(+5f)
                            },
                            enabled = !writingSv && currentSv != null,
                        ) { Text("+5", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

            }

    val SectionFanControl: @Composable () -> Unit = {
// ===== 风速控制条（双变量：手动滑块 + 跟随曲线目标，仅真机模式）=====
        if (useRealDevice) {
            Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(L10n.get("monitor.fan_title"), style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${fanSpeed.toInt()}%", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                 color = MaterialTheme.colorScheme.primary)
                        }
                        if (followMode && followFanTarget != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    L10n.get(
                                        if (fanManualOverride) "monitor.fan_manual" else "monitor.fan_target",
                                        "value" to followFanTarget!!.toInt()
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // 手动覆盖风速曲线后，可一键恢复风速曲线跟随（温度曲线不受影响）
                                if (fanManualOverride && activeProfile?.fanAnchors?.isNotEmpty() == true) {
                                    TextButton(onClick = { fanManualOverride = false }) {
                                        Text(L10n.get("monitor.fan_resume"),
                                             style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // 自动风速（RoR 自适应）开关：跟随中且模板带风速曲线时可用；手动覆盖时禁用
                    if (followMode && activeProfile?.fanAnchors?.isNotEmpty() == true) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                L10n.get("monitor.fan_auto"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Switch(
                                checked = fanAuto,
                                onCheckedChange = { fanAuto = it },
                                enabled = !fanManualOverride,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    Slider(
                        value = fanSpeed,
                        onValueChange = { fanSpeed = it },
                        onValueChangeFinished = {
                            // 双变量解耦：调风速只停风速曲线跟随（手动覆盖），温度曲线照常跟，不退出跟随
                            fanManualOverride = true
                            setFanSpeed(fanSpeed)
                        },
                        valueRange = 0f..100f,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    val SectionLcdCards: @Composable () -> Unit = {
// ===== LCD 读数卡片 =====
        val last = fullCurve.lastOrNull()
        val palette = if (isSystemInDarkTheme()) DarkRoast else WarmBeige
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcdCard(L10n.get("monitor.s39"), liveBt ?: last?.bt, palette.CurveBT, Modifier.weight(1f))
            TimeCard(displayTimeSec, palette.OnSurface, Modifier.weight(1f))
            LcdCard("RoR", last?.ror, palette.CurveRoR, Modifier.weight(1f), suffix = "°/m")
        }

        Spacer(Modifier.height(12.dp))

            }

    val SectionPhaseStats: @Composable () -> Unit = {
// ===== 烘焙阶段统计（标记黄点/一爆后自动出现）=====
        RoastPhaseStats(
            events = events.toList(),
            currentElapsedSec = displayTimeSec,
        )

            }

    val SectionChart: @Composable (Modifier) -> Unit = { cm ->
        // ===== 实时曲线图 =====
        val palette = if (isSystemInDarkTheme()) DarkRoast else WarmBeige
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
            Column {
                RoastChart(
                    data = ChartData(
                        liveCurve = fullCurve.toList(),
                        backgroundCurve = activeProfile?.points ?: emptyList(),
                        backgroundAnchors = activeProfile?.anchors ?: emptyList(),
                        events = events.toList(),
                        isRunning = useRealDevice,
                    ),
                    viewport = ChartViewport(timeMin = 0f, timeMax = 600f, tempMin = dynTempMin(fullCurve.toList())),
                    modifier = cm,
                )
                // 阶段图例：色块与背景带同色，解决色带辨识问题
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    LegendDot(palette.PhaseDrying, L10n.get("monitor.s40"))
                    LegendDot(palette.PhaseMaillard, L10n.get("monitor.s41"))
                    LegendDot(palette.PhaseDevelopment, L10n.get("monitor.s42"))
                    if (activeProfile != null) {
                        LegendDot(palette.CurveBackground, L10n.get("monitor.s43"))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

            }


    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val isWide = maxWidth >= 640.dp   // 折叠屏内屏展开约 677dp
        val dockPad = if (settings.showSecondCrack) 180.dp else 118.dp

        if (!isWide) {
            // —— 窄屏：单列滚动 ——
            Column(
                modifier = Modifier.fillMaxSize().widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = dockPad),
            ) {
                SectionHeader()
                SectionConnection()
                SectionModeCard()
                SectionSvControl()
                SectionFanControl()
                SectionLcdCards()
                SectionPhaseStats()
                SectionChart(Modifier.fillMaxWidth().height(320.dp))
            }
        } else {
            // —— 宽屏（折叠屏展开态）：左监视右控制台双栏仪表盘 ——
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionHeader()

                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 左栏：监视（不滚动——读数+统计常驻可见，图表吃掉剩余高度）
                    Column(
                        modifier = Modifier.weight(1.4f).fillMaxHeight().padding(bottom = dockPad),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionLcdCards()
                        SectionPhaseStats()
                        SectionChart(Modifier.fillMaxWidth().weight(1f))
                    }
                    // 右栏：控制台
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = dockPad),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionConnection()
                        SectionModeCard()
                        SectionSvControl()
                        SectionFanControl()
                    }
                }
            }
        }

        // ===== Artisan 导入说明框 =====
        var showAlogHint by remember { mutableStateOf(false) }
        if (showAlogHint) {
            AlertDialog(
                onDismissRequest = { showAlogHint = false },
                title = { Text(L10n.get("monitor.s44")) },
                text = {
                    Column {
                        Text(L10n.get("monitor.s45"),
                             style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(L10n.get("monitor.s46"), style = MaterialTheme.typography.labelMedium,
                             fontWeight = FontWeight.Bold)
                        Text(L10n.get("monitor.s47"),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(L10n.get("monitor.s48"), style = MaterialTheme.typography.labelMedium,
                             fontWeight = FontWeight.Bold)
                        Text(L10n.get("monitor.s49"),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showAlogHint = false
                        startArtisanImport()
                    }) { Text(L10n.get("monitor.s50")) }
                },
                dismissButton = {
                    TextButton(onClick = { showAlogHint = false }) { Text(L10n.get("common.cancel")) }
                },
            )
        }

        // ===== 模板选择弹窗 =====
        if (showProfilePicker) {
            var profiles by remember { mutableStateOf<List<RoastProfile>?>(null) }
            LaunchedEffect(Unit) { profiles = ProfileStore().listAll() }
            AlertDialog(
                onDismissRequest = { showProfilePicker = false },
                title = { Text(L10n.get("monitor.s51")) },
                text = {
                    when (val ps = profiles) {
                        null -> Text(L10n.get("monitor.s52"))
                        else -> if (ps.isEmpty()) {
                            Column {
                                Text(L10n.get("monitor.s53"))
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = {
                                    showProfilePicker = false
                                    onCreateProfile()
                                }, modifier = Modifier.fillMaxWidth()) { Text(L10n.get("monitor.s54")) }
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = {
                                    showProfilePicker = false
                                    showAlogHint = true
                                }, modifier = Modifier.fillMaxWidth()) { Text(L10n.get("monitor.s55")) }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                                items(ps.size) { idx ->
                                    val p = ps[idx]
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            Modifier
                                                .weight(1f)
                                                .clickable {
                                                    activeProfile = p
                                                    svHistory.clear()
                                                    hardBadSince = 0
                                                    followAlert = null
                                                    // 仅选中模板，不立刻跟随；跟随由 chip 或入豆自动触发
                                                    showProfilePicker = false
                                                }
                                                .padding(vertical = 10.dp),
                                        ) {
                                            Text(p.name, fontWeight = FontWeight.Bold)
                                            Text(
                                                L10n.get("monitor.s56", "size" to curvePoints.size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        // 编辑（仅自定义模板）：回炉改锚点
                                        if (p.sourceRecordId.isEmpty()) {
                                            TextButton(onClick = {
                                                showProfilePicker = false
                                                onEditProfile(p)
                                            }) {
                                                Text(L10n.get("monitor.s57"), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        // 删除模板：仅从列表移除，不影响历史记录
                                        TextButton(onClick = {
                                            scope.launch {
                                                ProfileStore().delete(p.id)
                                                profiles = ProfileStore().listAll()
                                            }
                                        }) {
                                            Text(L10n.get("monitor.s58"), color = MaterialTheme.colorScheme.error,
                                                 style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    val psSnap = profiles
                    Row {
                        if (psSnap != null && psSnap.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                showProfilePicker = false
                                onCreateProfile()
                            }) { Text(L10n.get("monitor.s59")) }
                            Spacer(Modifier.width(6.dp))
                        }
                        OutlinedButton(onClick = {
                            showProfilePicker = false
                            showAlogHint = true
                        }) { Text(L10n.get("common.import")) }
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { showProfilePicker = false }) { Text(L10n.get("common.cancel")) }
                    }
                },
            )
        }

        // ===== 豆袋同步：出豆后同步扣生豆库存（熟豆入库改为事后到详情页补录，称重更准） =====
        if (showBeanBagSync) {
            val bridge = remember { beanBagBridge() }
            var beans by remember { mutableStateOf<List<GreenBeanSummary>?>(null) }
            var loadError by remember { mutableStateOf<String?>(null) }   // 读取失败原因（区别于真空列表）
            var selectedId by remember { mutableStateOf<Long?>(null) }
            var gramsText by remember { mutableStateOf("") }
            var pushing by remember { mutableStateOf(false) }
            var resultMsg by remember { mutableStateOf<String?>(null) }
            var success by remember { mutableStateOf(false) }

            // 读取生豆：首次失败自动重试 2 次（间隔 400ms），仍失败才报错
            suspend fun loadBeans() {
                loadError = null
                beans = null
                var lastErr: String? = null
                for (attempt in 0..2) {
                    val r = bridge.listGreenBeans()
                    val list = r.getOrNull()
                    if (list != null) { beans = list; return }
                    lastErr = r.exceptionOrNull()?.message
                    if (attempt < 2) delay(400)
                }
                loadError = lastErr ?: L10n.get("monitor.s83")
            }
            LaunchedEffect(Unit) { loadBeans() }

            AlertDialog(
                onDismissRequest = { if (!pushing) showBeanBagSync = false },
                title = { Text(L10n.get("monitor.s60")) },
                text = {
                    when {
                        beans == null && loadError == null -> Text(L10n.get("monitor.s61"))
                        loadError != null && beans?.isEmpty() != false -> Column {
                            Text(L10n.get("monitor.s83"),  // 读取失败原因 + 引导
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(6.dp))
                            Text(L10n.get("monitor.s84"),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { scope.launch { loadBeans() } }) {
                                Text(L10n.get("monitor.s85"))  // 重试
                            }
                        }
                        beans!!.isEmpty() -> Column {
                            Text(L10n.get("monitor.s62"))
                            Spacer(Modifier.height(6.dp))
                            Text(L10n.get("monitor.s63"),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> Column {
                            Text(L10n.get("monitor.s86"),  // 只扣生豆，熟豆事后补录
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                items(beans!!.size) { idx ->
                                    val b = beans!![idx]
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { selectedId = b.id }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        androidx.compose.material3.RadioButton(
                                            selected = selectedId == b.id,
                                            onClick = { selectedId = b.id },
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(b.name, fontWeight = FontWeight.Bold)
                                            Text(L10n.get("monitor.s65", "remainingGrams" to b.remainingGrams.toInt()),
                                                 style = MaterialTheme.typography.labelSmall,
                                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = gramsText,
                                onValueChange = { gramsText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                label = { Text(L10n.get("monitor.s66")) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !success,
                            )
                            resultMsg?.let { msg ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (success) Color(0xFF3A7A44) else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    when {
                        success -> TextButton(onClick = { showBeanBagSync = false }) { Text(L10n.get("monitor.s69")) }
                        else -> TextButton(
                            enabled = selectedId != null && gramsText.toDoubleOrNull()?.let { it > 0 } == true && !pushing && pendingRoastId != null,
                            onClick = {
                                val rid = pendingRoastId ?: return@TextButton
                                val bid = selectedId ?: return@TextButton
                                val g = gramsText.toDoubleOrNull() ?: return@TextButton
                                pushing = true
                                scope.launch(Dispatchers.Main) {
                                    val res = bridge.consume(rid, bid, g)
                                    pushing = false
                                    when (res) {
                                        is BridgeResult.Ok -> {
                                            success = true
                                            // 只扣生豆；熟豆称重后到历史详情页补录入库
                                            resultMsg = "✓ ${res.message}\n" + L10n.get("monitor.s87")
                                        }
                                        is BridgeResult.Err -> resultMsg = L10n.get("monitor.s71", "message" to (res.message ?: ""))
                                    }
                                }
                            },
                        ) { Text(if (pushing) L10n.get("monitor.s72") else L10n.get("monitor.s73")) }
                    }
                },
                dismissButton = {
                    if (!success) TextButton(onClick = { showBeanBagSync = false }) { Text(L10n.get("monitor.s74")) }
                },
            )
        }


        // ===== 悬浮事件条：胶囊 dock，半透明底 + 咖色粗描边 =====
        val darkBar = isSystemInDarkTheme()
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(bottom = 8.dp)
                .navigationBarsPadding()
                .background(
                    if (darkBar) Color(0xFF261E14).copy(alpha = 0.72f)
                    else Color(0xFFF6F0E4).copy(alpha = 0.72f),
                    RoundedCornerShape(28.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (darkBar) Color(0xFF9C7850) else Color(0xFF6F4A32),
                    shape = RoundedCornerShape(28.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val rows: List<List<Pair<RoastEvent, String>>> =
                    if (settings.showSecondCrack) listOf(
                        listOf(RoastEvent.CHARGE to L10n.get("monitor.s75"), RoastEvent.DRY to L10n.get("monitor.s76"),
                               RoastEvent.FCs to L10n.get("monitor.s77"), RoastEvent.FCe to L10n.get("monitor.s78")),
                        listOf(RoastEvent.SCs to L10n.get("monitor.s79"), RoastEvent.SCe to L10n.get("monitor.s80"), RoastEvent.DROP to L10n.get("monitor.s81")),
                    ) else listOf(
                        listOf(RoastEvent.CHARGE to L10n.get("monitor.s75"), RoastEvent.DRY to L10n.get("monitor.s76"), RoastEvent.FCs to L10n.get("monitor.s77"),
                               RoastEvent.FCe to L10n.get("monitor.s78"), RoastEvent.DROP to L10n.get("monitor.s81")),
                    )
                rows.forEach { rowEvents ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowEvents.forEach { (event, label) ->
                    // 语义色：绿=入豆 黄=黄点 橙红=一爆 深红=一爆止 钢蓝=出豆
                    val dark = isSystemInDarkTheme()
                    // 未开表时仅「入豆」可点（投豆即正式开表）；其余事件置灰防误触无声失效
                    val canMark = recording || event == RoastEvent.CHARGE
                    val (container, content) = when (event) {
                        RoastEvent.CHARGE -> if (dark) Color(0xFF7CB87B) to Color(0xFF10200F) else Color(0xFF55804B) to Color.White
                        RoastEvent.DRY    -> if (dark) Color(0xFFE0C368) to Color(0xFF2A2208) else Color(0xFFB08A1E) to Color.White
                        RoastEvent.FCs    -> if (dark) Color(0xFFE89268) to Color(0xFF2A1204) else Color(0xFFC05A2E) to Color.White
                        RoastEvent.FCe    -> if (dark) Color(0xFFD07850) to Color(0xFF2A0E04) else Color(0xFF9E4526) to Color.White
                        RoastEvent.SCs    -> if (dark) Color(0xFFC25E6E) to Color(0xFF2A0A10) else Color(0xFF9E3040) to Color.White
                    RoastEvent.SCe    -> if (dark) Color(0xFFA84A5A) to Color(0xFF20060C) else Color(0xFF7E2434) to Color.White
                    RoastEvent.DROP   -> if (dark) Color(0xFF82A8D0) to Color(0xFF0A1626) else Color(0xFF4A6FA5) to Color.White
                        else              -> if (dark) Color(0xFF5A5248) to Color(0xFF14100A) else Color(0xFF7A6857) to Color.White
                    }
                    Button(
                        onClick = {
                            // 入豆：清空预热段数据，计时归零，曲线从入豆重新开始
                            if (event == RoastEvent.CHARGE && useRealDevice) {
                                displayTimeSec = 0f
                                recording = true   // 入豆即正式开表
                                channel?.resetTimer()
                                curvePoints.clear()
                                events.clear()
                                startTimeSec = 0f
                                // 生成本炉唯一 ID：出豆时同步豆袋扣库存的幂等键
                                pendingRoastId = "roast-" + kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                                saveSessionState()
                                // 设置开关：入豆自动开始跟随（需已选模板）
                                if (settings.autoFollowOnCharge) {
                                    if (activeProfile != null) {
                                        svHistory.clear()
                                        hardBadSince = 0
                                        followAlert = null
                                        followMode = true
                                    } else {
                                        followAlert = L10n.get("monitor.s82")
                                    }
                                }
                            }
                            // 出豆：先退出跟随（否则跟随控制器下一拍会把 SV 改回高温），
                            // 再自动把炉温设定降到 25°C 进入冷却。
                            // 关键：先等 writingSv 复位（在途跟随写完成）再降温，否则并发写可能
                            // 顺序颠倒（在途高温写后到，把刚降的 25°C 又改回高温）。
                            // sendCommand 内部 transactionMutex 会串行化，安全。
                            if (event == RoastEvent.DROP && useRealDevice) {
                                exitFollow()   // followMode=false，停止跟随控制器
                                val ch = channel
                                if (ch != null) {
                                    scope.launch(Dispatchers.Main) {
                                        // 等最多 3s 让在途跟随写完成（正常 ~1s 内）；超时也继续降温
                                        var waited = 0
                                        while (writingSv && waited < 15) { delay(200); waited++ }
                                        try {
                                            ch.sendCommand(DeviceCommand(CommandType.PID_SETPOINT, 25f))
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                persistSession()   // 出豆即存兑底快照，之后定时器继续覆盖更新
                                // 出豆后弹豆袋同步扣库存（若设置了入豆克重）
                                if (isBridgeAvailableOnPlatform()) showBeanBagSync = true
                            }
                            // 事件时间戳用「点击时刻的当前计时」而非最后采样点时间戳：
                            // 采样点可能滞后（轮询 1s 间隔 + 写 SV 阻塞轮询），用 lastP 会让事件时刻偏小，
                            // 导致美拉德段（黄点→一爆）等阶段计时不准（2026-08-30 实锤）。
                            val evtNow = channel?.elapsedSec() ?: fullCurve.lastOrNull()?.timeSeconds ?: 0f
                            val lastP = if (event == RoastEvent.CHARGE && useRealDevice) null else fullCurve.lastOrNull()
                            val t = if (event == RoastEvent.CHARGE) 0f else evtNow   // 入豆锚定 0，其余用点击时刻
                            // 记录事件时刻的豆温，图表与阶段卡都会用到
                            events.add(EventMarker(event, t, temperature = lastP?.bt ?: 0f))
                            // 真机模式下同步到通道（温控器本地记录，无远端动作）
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                        enabled = canMark,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = container.copy(alpha = if (canMark) 1f else 0.35f),
                            contentColor = content,
                        ),
                    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                }
            }
        }
    } // BoxWithConstraints
}

/** 图例小项：色块 + 名称 */
@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * LCD 风格读数卡片
 */
@Composable
private fun LcdCard(
    label: String,
    value: Float?,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    suffix: String = "°C",
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            Text(
                text = value?.toFixed1() ?: "--",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(suffix, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 烘焙计时卡片（m:ss）
 */
@Composable
private fun TimeCard(
    seconds: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val m = (seconds / 60).toInt()
    val s = (seconds % 60).toInt()
    // 跳秒诊断：打印 UI 层实际拿到的秒数与转换结果，定位「跳」发生在哪一层
    println("TIMECARD sec=$seconds m=$m s=$s")
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("TIME", style = MaterialTheme.typography.labelMedium, color = color)
            Text(
                text = if (s < 10) "$m:0$s" else "$m:$s",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            )
            Text("min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}