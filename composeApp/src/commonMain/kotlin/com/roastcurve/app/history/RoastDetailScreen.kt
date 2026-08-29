package com.roastcurve.app.history

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.roastcurve.app.monitor.dynTempMin
import com.roastcurve.app.monitor.RoastPhaseStats
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roastcurve.app.chart.ChartData
import com.roastcurve.app.chart.ChartViewport
import com.roastcurve.app.chart.RoastChart
import com.roastcurve.shared.model.RoastEvent
import com.roastcurve.shared.math.RoastMath
import com.roastcurve.shared.model.RoastProfile
import com.roastcurve.shared.storage.ProfileStore
import com.roastcurve.shared.model.RoastRecord
import com.roastcurve.shared.storage.RoastStore
import com.roastcurve.shared.bridge.BeanBagBridge
import com.roastcurve.shared.bridge.GreenBeanSummary
import com.roastcurve.shared.bridge.BridgeResult
import com.roastcurve.shared.bridge.beanBagBridge
import com.roastcurve.shared.bridge.isBridgeAvailableOnPlatform

/**
 * 历史烘焙回看（只读曲线 + 导出）
 */
@Composable
fun RoastDetailScreen(
    record: RoastRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = remember { RoastStore() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    // 本地可变副本：重命名/补录后即时刷新显示（record 参数不可变）
    var currentRecord by remember { mutableStateOf(record) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(record.beanName) }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(L10n.get("detail.s1")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(L10n.get("detail.s2")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    scope.launch {
                        val updated = currentRecord.copy(beanName = newName)
                        store.save(updated)
                        currentRecord = updated
                    }
                    showRename = false
                }) { Text(L10n.get("detail.s3")) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text(L10n.get("detail.s4")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.delete(record.id)
                        onBack()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 640.dp

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack) { Text(L10n.get("detail.s5")) }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    var savedMsg by remember { mutableStateOf(false) }
                    TextButton(onClick = {
                        renameText = currentRecord.beanName
                        showRename = true
                    }) { Text(L10n.get("detail.s6")) }
                    TextButton(onClick = {
                        scope.launch {
                            val dropT = record.events.find { it.event == RoastEvent.DROP }?.timeSeconds
                            val pts = if (dropT != null) record.curveData.filter { it.timeSeconds <= dropT + 1f } else record.curveData
                            val nowMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                            ProfileStore().save(
                                RoastProfile(
                                    id = RoastStore.newId(nowMs),
                                    name = L10n.get("detail.s7", "nowMs" to (nowMs % 100000)),
                                    sourceRecordId = record.id,
                                    points = pts,
                                )
                            )
                            savedMsg = true
                        }
                    }) { Text(if (savedMsg) L10n.get("detail.s8") else L10n.get("detail.s9")) }
                    TextButton(onClick = {
                        val csv = RoastStore.toCsv(record)
                        shareText("roast_${record.id}.csv", csv)
                    }) { Text(L10n.get("detail.s10")) }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (!isWide) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
                ) {
                    val dateText = if (record.id.length >= 15) {
                        "${record.id.substring(0,4)}-${record.id.substring(4,6)}-${record.id.substring(6,8)} ${record.id.substring(9)}"
                    } else record.id
                    Text(dateText, style = MaterialTheme.typography.titleMedium,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${currentRecord.beanName.ifEmpty { "未命名" }} · ${currentRecord.curveData.size}个采样点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    RoastPhaseStats(
                        events = record.events,
                        currentElapsedSec = record.totalTimeSeconds,
                    )
                    Spacer(Modifier.height(8.dp))
                    WeightLossCard(currentRecord)
                    Spacer(Modifier.height(8.dp))
                    BeanBagSyncCard(currentRecord)
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                        RoastChart(
                            data = ChartData(
                                liveCurve = remember(record) {
                                    val dropT = record.events.find { it.event == RoastEvent.DROP }?.timeSeconds
                                    val pts = if (dropT != null) record.curveData.filter { it.timeSeconds <= dropT + 1f } else record.curveData
                                    RoastMath.withRor(pts)
                                },
                                backgroundCurve = emptyList(),
                                events = record.events,
                                isRunning = false,
                            ),
                            viewport = ChartViewport(autoScroll = false, tempMin = dynTempMin(record.curveData)),
                            modifier = Modifier.fillMaxWidth().height(360.dp),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val dateText = if (record.id.length >= 15) {
                        "${record.id.substring(0,4)}-${record.id.substring(4,6)}-${record.id.substring(6,8)} ${record.id.substring(9)}"
                    } else record.id
                    Text("${dateText} · ${currentRecord.beanName.ifEmpty { "未命名" }}",
                         style = MaterialTheme.typography.titleMedium,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)

                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 2.dp,
                            modifier = Modifier.weight(1.2f).fillMaxHeight(),
                        ) {
                            RoastChart(
                                data = ChartData(
                                    liveCurve = remember(record) {
                                        val dropT = record.events.find { it.event == RoastEvent.DROP }?.timeSeconds
                                        val pts = if (dropT != null) record.curveData.filter { it.timeSeconds <= dropT + 1f } else record.curveData
                                        RoastMath.withRor(pts)
                                    },
                                    backgroundCurve = emptyList(),
                                    events = record.events,
                                    isRunning = false,
                                ),
                                viewport = ChartViewport(autoScroll = false, tempMin = dynTempMin(record.curveData)),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RoastPhaseStats(
                                events = record.events,
                                currentElapsedSec = record.totalTimeSeconds,
                            )
                            WeightLossCard(currentRecord)
                            BeanBagSyncCard(currentRecord)
                        }
                    }
                }
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("删除这条记录？") },
                text = { Text(L10n.get("detail.s4")) },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            store.delete(record.id)
                            onBack()
                        }
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            )
        }
    }
}

/**
 * 重量与失重率卡片：补录生豆重/熟豆重，实时计算失重率并保存
 * 失重率 = (生豆重 - 熟豆重) / 生豆重 × 100%，典型区间 12%~18%
 */
@Composable
private fun WeightLossCard(record: RoastRecord) {
    val scope = rememberCoroutineScope()
    val store = remember { RoastStore() }
    var chargeText by remember { mutableStateOf(if (record.beanWeight > 0f) record.beanWeight.toInt().toString() else "") }
    var dropText by remember { mutableStateOf(if (record.dropWeight > 0f) record.dropWeight.toInt().toString() else "") }
    var saved by remember { mutableStateOf(false) }

    val charge = chargeText.toFloatOrNull()
    val drop = dropText.toFloatOrNull()
    val loss = if (charge != null && charge > 0f && drop != null && drop > 0f && drop <= charge)
        RoastMath.calculateWeightLoss(charge, drop) else null

    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(L10n.get("detail.s11"), style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = chargeText,
                    onValueChange = { s ->
                        chargeText = s
                        saved = false
                    },
                    label = { Text(L10n.get("detail.s12")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                )
                OutlinedTextField(
                    value = dropText,
                    onValueChange = { s ->
                        dropText = s
                        saved = false
                    },
                    label = { Text(L10n.get("detail.s13")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L10n.get("detail.s14"), style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        loss?.let { ((kotlin.math.round(it * 10f) / 10f).toString()) + "%" } ?: "--",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (loss != null && loss !in 8f..25f)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        store.save(
                            record.copy(
                                beanWeight = charge ?: 0f,
                                dropWeight = drop ?: 0f,
                            )
                        )
                        saved = true
                    }
                }) { Text(if (saved) L10n.get("detail.s15") else L10n.get("detail.s3")) }
            }
            if (loss != null && loss !in 8f..25f) {
                Text(L10n.get("detail.s16"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 豆袋补录卡片（手动修正入口）：把本炉同步到豆袋。
 * 场景：老版本同步失败/跳过、或只扣了生豆没入熟豆的历史记录。
 * 幂等由豆袋端保证，重复推送安全。
 */
@Composable
private fun BeanBagSyncCard(record: RoastRecord) {
    // 平台不支持（iOS）时不显示
    if (!isBridgeAvailableOnPlatform()) return

    val bridge = remember { beanBagBridge() }
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }

    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(L10n.get("detail.s17"), style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        L10n.get("detail.s18"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { show = true }) { Text(L10n.get("detail.s19")) }
            }
        }
    }

    if (show) {
        BeanBagSyncDialog(record = record, bridge = bridge) { show = false }
    }
}

/** 豆袋补录弹窗：两种模式（全量 / 只补熟豆） */
@Composable
private fun BeanBagSyncDialog(
    record: RoastRecord,
    bridge: BeanBagBridge,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 只补熟豆模式默认勾选：因为多数补录场景生豆已扣过，只缺熟豆入库
    var onlyRoasted by remember { mutableStateOf(true) }
    var greenId by remember { mutableStateOf<Long?>(null) }
    var greenGramsText by remember { mutableStateOf(record.beanWeight.takeIf { it > 0f }?.toInt()?.toString() ?: "") }
    var roastedName by remember { mutableStateOf(record.beanName) }
    var roastedGramsText by remember { mutableStateOf(record.dropWeight.takeIf { it > 0f }?.toInt()?.toString() ?: "") }
    var beans by remember { mutableStateOf<List<GreenBeanSummary>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(onlyRoasted) {
        if (!onlyRoasted && beans == null) beans = bridge.listGreenBeans()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(L10n.get("detail.s20")) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onlyRoasted, onCheckedChange = { onlyRoasted = it }, enabled = !busy && !done)
                    Text(L10n.get("detail.s21"), style = MaterialTheme.typography.bodyMedium)
                }
                if (!onlyRoasted) {
                    when {
                        beans == null -> Text("读取豆袋生豆批次…")
                        beans!!.isEmpty() -> Text(L10n.get("detail.s22"))
                        else -> {
                            LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                                items(beans!!.size) { idx ->
                                    val b = beans!![idx]
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { greenId = b.id }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        androidx.compose.material3.RadioButton(
                                            selected = greenId == b.id,
                                            onClick = { greenId = b.id },
                                        )
                                        Text(L10n.get("detail.s23", "name" to b.name, "remainingGrams" to b.remainingGrams.toInt()))
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = greenGramsText,
                                onValueChange = { greenGramsText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                label = { Text("生豆消耗克重（入豆重）") },
                                singleLine = true,
                                enabled = !busy && !done,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = roastedName,
                    onValueChange = { roastedName = it },
                    label = { Text("熟豆名称（同名累加库存）") },
                    singleLine = true,
                    enabled = !busy && !done,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = roastedGramsText,
                    onValueChange = { roastedGramsText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text(L10n.get("detail.s24")) },
                    singleLine = true,
                    enabled = !busy && !done,
                    modifier = Modifier.fillMaxWidth(),
                )
                msg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (done) Color(0xFF3A7A44) else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !done &&
                    (onlyRoasted || greenId != null) &&
                    (roastedGramsText.toDoubleOrNull()?.let { it > 0 } == true || !onlyRoasted),
                onClick = {
                    busy = true
                    scope.launch(Dispatchers.Main) {
                        try {
                            val parts = mutableListOf<String>()
                            // ① 生豆扣减（非只补熟豆模式）
                            if (!onlyRoasted) {
                                val gid = greenId
                                val g = greenGramsText.toDoubleOrNull() ?: 0.0
                                if (gid == null || g <= 0.0) {
                                    msg = L10n.get("detail.s25")
                                    busy = false
                                    return@launch
                                }
                                when (val r = bridge.consume(record.id, gid, g)) {
                                    is BridgeResult.Ok -> parts.add("✓ ${r.message}")
                                    is BridgeResult.Err -> {
                                        msg = L10n.get("detail.s26", "message" to r.message)
                                        busy = false
                                        return@launch
                                    }
                                }
                            }
                            // ② 熟豆入库
                            val rg = roastedGramsText.toDoubleOrNull() ?: 0.0
                            if (rg > 0) {
                                when (val r2 = bridge.addRoasted(
                                    roastId = record.id,
                                    beanName = roastedName.trim(),
                                    roastedGrams = rg,
                                    roastLevel = record.roastLevel,
                                    roastDateEpochMs = record.roastDate?.toEpochMilliseconds()
                                        ?: kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                                )) {
                                    is BridgeResult.Ok -> parts.add("✓ ${r2.message}")
                                    is BridgeResult.Err -> parts.add(L10n.get("detail.s27", "message" to (r2.message ?: "")))
                                }
                            }
                            done = true
                            msg = parts.joinToString("\n").ifEmpty { L10n.get("detail.s28") }
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "推送中…" else if (done) "完成" else L10n.get("detail.s29")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (done) L10n.get("detail.s30") else "取消") }
        },
    )
}

/** 平台分享（Android 分享面板 / iOS 活动视图） */
expect fun shareText(filename: String, content: String)

/**
 * 备份文件直接写入公共下载目录（Android 10+ MediaStore，免权限免分享面板）
 * @return 保存位置描述；null 表示平台不支持（用分享兜底）
 */
expect fun exportBackupToDownloads(filename: String, data: ByteArray): String?

/** 把备份 JSON 打包成 zip 字节流（Android 实现；不支持的平台返回 null） */
expect fun packBackupZip(filename: String, json: String): ByteArray?

/** 从 zip 字节流里取出 backup.json 文本；输入若是纯 JSON 文本则原样返回 */
expect fun unpackBackupZip(data: ByteArray): String?