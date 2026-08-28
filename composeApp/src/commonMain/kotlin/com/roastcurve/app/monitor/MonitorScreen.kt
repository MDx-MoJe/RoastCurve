package com.roastcurve.app.monitor

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
    var fuseBadSec by remember { mutableStateOf(0) }          // 偏差熔断计数
    val svHistory = remember { mutableListOf<Float>() }        // SV 平滑窗口（Artisan 同款 5 点）
    var followClock by remember { mutableStateOf(0f) }         // 诊断：模板时钟
    var followTarget by remember { mutableStateOf<Float?>(null) }   // 诊断：当前目标温度
    var followLastSv by remember { mutableStateOf<Float?>(null) }   // 诊断：最近回读的 SV

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
                connectionError = "设定失败: ${e.message}"
            } finally {
                writingSv = false
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
                connectionError = "设定失败: ${e.message}"
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
                            ?: run { followAlert = "无法识别的 .alog 文件"; return@launch }
                        val pts = com.roastcurve.shared.io.ArtisanAlog.toPoints(curve)
                        val computedEvents = com.roastcurve.shared.io.ArtisanAlog.parseComputedEvents(text)
                        val anchors = com.roastcurve.shared.io.ArtisanAlog.deriveAnchors(pts, curve.phases, computedEvents)
                        val nm = nameHint?.removeSuffix(".alog")?.removeSuffix(".ALOG")
                            ?.ifBlank { null }
                            ?: curve.title ?: "Artisan 曲线"
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
                            "已导入模板：$nm（${pts.size} 点，含 ${computedEvents.size} 个事件锚点）"
                        else
                            "已导入模板：$nm（${pts.size} 点）；脱水/一爆等锚点可在编辑器中补充标注"
                        activeProfile = ProfileStore().listAll().find { it.id.startsWith("2026") && it.name == nm } ?: activeProfile
                        showProfilePicker = true   // 重开选择器展示结果
                    } catch (e: Exception) {
                        followAlert = "导入失败：${e.message?.take(40)}"
                    }
                }
            }
        }
        com.roastcurve.shared.BackupBridge.requestPick?.invoke()
    }

    fun connect(host: String) {
        connectionError = null
        scope.launch(Dispatchers.Main) {
            try {
                var lastError: Exception? = null
                var ch: DeviceChannel? = null
                // 重试 3 次（首次失败常是网络栈瞬态，间隔递增）
                for (attempt in 1..3) {
                    try {
                        ch = when (linkType) {
                            LinkType.MODBUS_TCP -> ModbusTcpChannel(name = "温控器", host = host)
                            LinkType.TCP_TRANSPARENT -> TransparentChannel(
                                name = "温控器",
                                transport = TcpByteTransport(host = host, port = 8899),
                            )
                            LinkType.BLE_TRANSPARENT -> TransparentChannel(
                                name = "温控器",
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
                                val shifted = point.copy(timeSeconds = point.timeSeconds + timerOffsetSec)
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
                            println("RC_TICK rec=$recording ch=${ch.elapsedSec()} disp=$displayTimeSec")
                            if (recording) displayTimeSec = ch.elapsedSec() + timerOffsetSec
                            delay(200)
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                    connectionError = when {
                        "占用" in msg || "Artisan" in msg -> msg
                        // 瞬时网络错误：3 次重试后仍失败多半是设备不在线，不要照搬系统层原文误导用户
                        "route to host" in msg || "EHOSTUNREACH" in msg || "ENETUNREACH" in msg ->
                            "连接不上桥接器：请确认设备已上电、手机与它在同一 WiFi（多次重试均失败）"
                        else -> "连接失败: ${msg.ifEmpty { e::class.simpleName }}"
                    }
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
        while (followMode && useRealDevice && activeProfile != null) {
            delay(2000)
            val profile = activeProfile ?: break
            val lastP = curvePoints.lastOrNull()
            val t = lastP?.timeSeconds ?: startTimeSec
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
            if (raw == null || rawAtT == null) {
                exitFollow("模板曲线已走完，已切回手动")
                break
            }
            followTarget = raw
            // Artisan 同款五点衰减加权平滑，抹平台阶
            svHistory.add(raw)
            if (svHistory.size > 5) svHistory.removeAt(0)
            val target = RoastMath.smoothSv(svHistory).coerceIn(0f, 260f)

            // 偏差熔断：仅在入豆后生效，且 |实际−目标| >15°C 连续 30 秒 → 自动切手动
            val bt = lastP?.bt
            if (chargeT != null && bt != null && kotlin.math.abs(bt - raw) > 15f) {
                fuseBadSec += 2
                if (fuseBadSec >= 30) {
                    exitFollow("偏差超过 15°C 持续 30 秒，已自动切回手动")
                    break
                }
            } else {
                fuseBadSec = 0
            }

            // 变化 ≥1° 才写寄存器（同 Artisan 去重写入）
            val cur = currentSv
            followLastSv = cur
            if (cur != null && kotlin.math.abs(target - cur) >= 1f && !writingSv) {
                setSvAbsolute(target)
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
            followAlert = "检测到上次未完成的烘焙会话，已恢复；请重新连接设备继续"
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
            Text("烤豆 · 监控", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onOpenHistory) { Text("记录") }
                OutlinedButton(onClick = onOpenSettings) { Text("设置") }
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
                    }) { Text("开始") }
                }
                if (useRealDevice && recording) {
                    Button(onClick = { stopRecording() }) { Text("停止") }
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
                LinkType.TCP_TRANSPARENT to "串口透传",
                LinkType.BLE_TRANSPARENT to "蓝牙",
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
                label = { Text(if (linkType == LinkType.BLE_TRANSPARENT) "蓝牙地址 (MAC)" else "桥接器 IP") },
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
                Text(if (useRealDevice) "断开" else "连接")
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
                ) { Text("知道了", style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (useRealDevice) {
            Text(
                "已连接温控器（实时）",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
            // 信号强度指示：绿/黄/红三档 + 具体 dBm
            bridgeRssi?.let { rssi ->
                Spacer(Modifier.width(8.dp))
                val (sigColor, sigLabel) = when {
                    rssi >= -60 -> MaterialTheme.colorScheme.primary to "强"
                    rssi >= -70 -> Color(0xFFC08A00) to "中"
                    rssi >= -80 -> Color(0xFFC05A2E) to "弱"
                    else -> MaterialTheme.colorScheme.error to "极弱"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(sigColor)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "信号${sigLabel} $rssi dBm",
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
                        Text("烘焙模式", style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = !followMode,
                                onClick = { if (followMode) exitFollow() },
                                label = { Text("手动") },
                            )
                            FilterChip(
                                selected = followMode,
                                onClick = {
                                    when {
                                        followMode -> exitFollow()
                                        activeProfile == null -> showProfilePicker = true
                                        else -> { svHistory.clear(); fuseBadSec = 0; followMode = true; followAlert = null }
                                    }
                                },
                                label = { Text("跟随曲线") },
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
                                "模板：${prof.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            TextButton(onClick = { showProfilePicker = true }) {
                                Text("更换", style = MaterialTheme.typography.labelSmall)
                            }
                            // 实时偏差（跟随中显示数字，手动时提示参照）
                            val dev = fullCurve.lastOrNull()?.bt?.let { bt ->
                                RoastMath.profileTargetAt(prof.points, fullCurve.lastOrNull()?.timeSeconds ?: 0f)?.let { bt - it }
                            }
                            Text(
                                when {
                                    !followMode -> "参照中"
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
                        Text("未选模板：点「跟随曲线」选择一炉历史记录作为目标",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (followMode) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "诊断：目标 ${followTarget?.toInt()?.toString() ?: "--"}° · 回读SV ${followLastSv?.toInt()?.toString() ?: "--"}° · 模板时钟 ${followClock.toInt()}s",
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
                            Text("知道了", style = MaterialTheme.typography.labelSmall)
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
                        Text("SV 设定温度", style = MaterialTheme.typography.labelMedium,
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
                                if (followMode) exitFollow("已切回手动模式")
                                adjustSv(-5f)
                            },
                            enabled = !writingSv && currentSv != null,
                        ) { Text("-5", fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = {
                                if (followMode) exitFollow("已切回手动模式")
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

    val SectionLcdCards: @Composable () -> Unit = {
// ===== LCD 读数卡片 =====
        val last = fullCurve.lastOrNull()
        val palette = if (isSystemInDarkTheme()) DarkRoast else WarmBeige
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcdCard("BT 豆温", liveBt ?: last?.bt, palette.CurveBT, Modifier.weight(1f))
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
                    LegendDot(palette.PhaseDrying, "脱水")
                    LegendDot(palette.PhaseMaillard, "美拉德")
                    LegendDot(palette.PhaseDevelopment, "发展")
                    if (activeProfile != null) {
                        LegendDot(palette.CurveBackground, "模板")
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
                    }
                }
            }
        }

        // ===== Artisan 导入说明框 =====
        var showAlogHint by remember { mutableStateOf(false) }
        if (showAlogHint) {
            AlertDialog(
                onDismissRequest = { showAlogHint = false },
                title = { Text("导入 Artisan 曲线") },
                text = {
                    Column {
                        Text("将从 .alog 文件读取温度曲线，生成可跟随的模板。",
                             style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("✓ 会导入", style = MaterialTheme.typography.labelMedium,
                             fontWeight = FontWeight.Bold)
                        Text("· 温度曲线（时间 + 豆温）\n· 入豆 / 出豆 时间锚点",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("✓ 自动识别事件节点", style = MaterialTheme.typography.labelMedium,
                             fontWeight = FontWeight.Bold)
                        Text("若 .alog 自带 computed 事件（入豆/脱水/一爆/出豆的时间与温度），导入时直接读取为锚点；缺失时回退为温度阈值推导。",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showAlogHint = false
                        startArtisanImport()
                    }) { Text("选择文件") }
                },
                dismissButton = {
                    TextButton(onClick = { showAlogHint = false }) { Text("取消") }
                },
            )
        }

        // ===== 模板选择弹窗 =====
        if (showProfilePicker) {
            var profiles by remember { mutableStateOf<List<RoastProfile>?>(null) }
            LaunchedEffect(Unit) { profiles = ProfileStore().listAll() }
            AlertDialog(
                onDismissRequest = { showProfilePicker = false },
                title = { Text("选择烘焙模板") },
                text = {
                    when (val ps = profiles) {
                        null -> Text("读取中…")
                        else -> if (ps.isEmpty()) {
                            Column {
                                Text("还没有模板。可点「+ 新建自定义模板」「导入 Artisan 曲线」，或到历史详情页「存为模板」。")
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = {
                                    showProfilePicker = false
                                    onCreateProfile()
                                }, modifier = Modifier.fillMaxWidth()) { Text("+ 新建自定义模板") }
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = {
                                    showProfilePicker = false
                                    showAlogHint = true
                                }, modifier = Modifier.fillMaxWidth()) { Text("导入 Artisan 曲线 (.alog)") }
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
                                                    fuseBadSec = 0
                                                    followAlert = null
                                                    // 仅选中模板，不立刻跟随；跟随由 chip 或入豆自动触发
                                                    showProfilePicker = false
                                                }
                                                .padding(vertical = 10.dp),
                                        ) {
                                            Text(p.name, fontWeight = FontWeight.Bold)
                                            Text(
                                                "${p.points.size} 个采样点",
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
                                                Text("编辑", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        // 删除模板：仅从列表移除，不影响历史记录
                                        TextButton(onClick = {
                                            scope.launch {
                                                ProfileStore().delete(p.id)
                                                profiles = ProfileStore().listAll()
                                            }
                                        }) {
                                            Text("删除", color = MaterialTheme.colorScheme.error,
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
                            }) { Text("+ 新建") }
                            Spacer(Modifier.width(6.dp))
                        }
                        OutlinedButton(onClick = {
                            showProfilePicker = false
                            showAlogHint = true
                        }) { Text("导入") }
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { showProfilePicker = false }) { Text("取消") }
                    }
                },
            )
        }

        // ===== 豆袋同步：出豆后同步扣生豆库存 =====
        if (showBeanBagSync) {
            val bridge = remember { beanBagBridge() }
            var beans by remember { mutableStateOf<List<GreenBeanSummary>?>(null) }
            var selectedId by remember { mutableStateOf<Long?>(null) }
            var gramsText by remember { mutableStateOf("") }
            var pushing by remember { mutableStateOf(false) }
            var resultMsg by remember { mutableStateOf<String?>(null) }
            var success by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) { beans = bridge.listGreenBeans() }

            AlertDialog(
                onDismissRequest = { if (!pushing) showBeanBagSync = false },
                title = { Text("同步到豆袋") },
                text = {
                    when {
                        beans == null -> Text("读取豆袋生豆批次…")
                        beans!!.isEmpty() -> Column {
                            Text("未读到豆袋生豆批次。请确认：")
                            Spacer(Modifier.height(6.dp))
                            Text("· 豆袋已安装且为 3.0.13 或以上版本\n· 豆袋里有生豆批次记录",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> Column {
                            Text("选择生豆批次并确认克重，豆袋将自动扣减库存。重复推送不会重复扣减。",
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
                                            Text("剩余 ${b.remainingGrams}g",
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
                                label = { Text("本次消耗克重（入豆重）") },
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
                        success -> TextButton(onClick = { showBeanBagSync = false }) { Text("完成") }
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
                                        is BridgeResult.Ok -> { success = true; resultMsg = "✓ ${res.message}" }
                                        is BridgeResult.Err -> resultMsg = "扣减失败：${res.message}"
                                    }
                                }
                            },
                        ) { Text(if (pushing) "推送中…" else "确认扣减") }
                    }
                },
                dismissButton = {
                    if (!success) TextButton(onClick = { showBeanBagSync = false }) { Text("跳过") }
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
                        listOf(RoastEvent.CHARGE to "入豆", RoastEvent.DRY to "黄点",
                               RoastEvent.FCs to "一爆", RoastEvent.FCe to "一爆止"),
                        listOf(RoastEvent.SCs to "二爆", RoastEvent.SCe to "二爆止", RoastEvent.DROP to "出豆"),
                    ) else listOf(
                        listOf(RoastEvent.CHARGE to "入豆", RoastEvent.DRY to "黄点", RoastEvent.FCs to "一爆",
                               RoastEvent.FCe to "一爆止", RoastEvent.DROP to "出豆"),
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
                                        fuseBadSec = 0
                                        followAlert = null
                                        followMode = true
                                    } else {
                                        followAlert = "已入豆，未选模板，当前手动模式；可点「跟随曲线」选择模板开始自动跟随"
                                    }
                                }
                            }
                            // 出豆：自动把炉温设定降到25°C进入冷却，免手动调整
                            if (event == RoastEvent.DROP && useRealDevice) {
                                val ch = channel
                                if (ch != null && !writingSv) {
                                    writingSv = true
                                    scope.launch(Dispatchers.Main) {
                                        try {
                                            ch.sendCommand(DeviceCommand(CommandType.PID_SETPOINT, 25f))
                                        } catch (_: Exception) {
                                        } finally { writingSv = false }
                                    }
                                }
                                persistSession()   // 出豆即存兑底快照，之后定时器继续覆盖更新
                                // 出豆后弹豆袋同步扣库存（若设置了入豆克重）
                                if (isBridgeAvailableOnPlatform()) showBeanBagSync = true
                            }
                            val lastP = if (event == RoastEvent.CHARGE && useRealDevice) null else fullCurve.lastOrNull()
                            val t = lastP?.timeSeconds ?: 0f   // 入豆后从 0 起，作为跟随时钟锚点
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