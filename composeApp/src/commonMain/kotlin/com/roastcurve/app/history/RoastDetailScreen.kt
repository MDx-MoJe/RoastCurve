package com.roastcurve.app.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.roastcurve.app.monitor.dynTempMin
import com.roastcurve.app.monitor.RoastPhaseStats
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            title = { Text("重命名记录") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("豆子名称（如：耶加雪菲 水洗）") },
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
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("删除后无法恢复") },
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
                OutlinedButton(onClick = onBack) { Text("← 返回") }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    var savedMsg by remember { mutableStateOf(false) }
                    TextButton(onClick = {
                        renameText = currentRecord.beanName
                        showRename = true
                    }) { Text("重命名") }
                    TextButton(onClick = {
                        scope.launch {
                            val dropT = record.events.find { it.event == RoastEvent.DROP }?.timeSeconds
                            val pts = if (dropT != null) record.curveData.filter { it.timeSeconds <= dropT + 1f } else record.curveData
                            val nowMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                            ProfileStore().save(
                                RoastProfile(
                                    id = RoastStore.newId(nowMs),
                                    name = "模板 ${record.id}",
                                    sourceRecordId = record.id,
                                    points = pts,
                                )
                            )
                            savedMsg = true
                        }
                    }) { Text(if (savedMsg) "已保存✓" else "存为模板") }
                    TextButton(onClick = {
                        val csv = RoastStore.toCsv(record)
                        shareText("roast_${record.id}.csv", csv)
                    }) { Text("导出CSV") }
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
                        }
                    }
                }
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("删除这条记录？") },
                text = { Text("删除后无法恢复") },
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
            Text("重量与失重率", style = MaterialTheme.typography.labelMedium,
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
                    label = { Text("生豆重 g") },
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
                    label = { Text("熟豆重 g") },
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
                    Text("失重率", style = MaterialTheme.typography.labelSmall,
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
                }) { Text(if (saved) "已存✓" else "保存") }
            }
            if (loss != null && loss !in 8f..25f) {
                Text("⚠ 失重率超出常见区间（8%~25%），确认克重是否输错",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
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