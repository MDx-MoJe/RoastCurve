/*
 * RoastCurve（烤豆）—— 咖啡烘焙曲线记录与控制
 * Copyright 2026 MDx
 * https://github.com/MDx-MoJe/RoastCurve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.roastcurve.app.diag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roastcurve.app.history.exportBackupToDownloads
import com.roastcurve.shared.diag.DiagLog
import com.roastcurve.shared.diag.DiagReport
import com.roastcurve.shared.diag.DiagStatus
import com.roastcurve.shared.diag.DiagStep
import com.roastcurve.shared.diag.runConnectDiagnostics
import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.model.Settings
import com.roastcurve.shared.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 连接诊断页
 *
 * 用户视角要的是一句能动手的话，不是异常类名。
 * 所以每层探测都翻译成「哪一环断了 + 怎么办」，同时把原始证据收进可导出的诊断包。
 */
@Composable
fun DiagScreen(
    settings: Settings,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(settings.lastBridgeHost) }
    var running by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<DiagStep>>(emptyList()) }
    var report by remember { mutableStateOf<DiagReport?>(null) }
    var expandedRaw by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var logTick by remember { mutableStateOf(0) }

    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(5000)
            toast = null
        }
    }

    fun runDiag() {
        if (running) return
        running = true
        steps = emptyList()
        report = null
        expandedRaw = null
        DiagLog.info("DIAG", "开始诊断 host=$host")
        scope.launch {
            try {
                val r = runConnectDiagnostics(
                    hostRaw = host,
                    slaveId = settings.modbusSlaveId,
                    pvReg = settings.modbusPvReg,
                    svReg = settings.modbusSvReg,
                    baud = settings.modbusBaud.toLong(),
                    onStep = { s -> steps = steps + s },
                )
                report = r
                DiagLog.info("DIAG", "诊断完成：${r.verdict}")
                // 记住这次用的地址，方便用户下次直接重连
                if (host.isNotBlank() && host != settings.lastBridgeHost) {
                    runCatching {
                        val s = SettingsStore()
                        s.save(s.load().copy(lastBridgeHost = host.trim()))
                    }
                }
            } catch (e: Exception) {
                DiagLog.error("DIAG", "诊断异常：${e.message}")
                toast = L10n.get("diag.export_fail")
            } finally {
                running = false
                logTick++
            }
        }
    }

    fun exportPackage() {
        val r = report ?: return
        scope.launch {
            val zip = withContext(Dispatchers.Default) {
                val stamp = timestamp()
                val summary = buildString {
                    appendLine("RoastCurve 连接诊断报告")
                    appendLine("时间: $stamp")
                    appendLine("桥接器: ${r.host}")
                    appendLine()
                    appendLine("结论: ${r.verdict}")
                    appendLine("建议: ${r.advice}")
                    appendLine()
                    appendLine("--- 逐层结果 ---")
                    r.steps.forEach { s ->
                        appendLine("${statusMark(s.status)} ${s.title}: ${s.detail}")
                        if (s.raw.isNotBlank()) appendLine("    raw: ${s.raw.replace("\n", "\n         ")}")
                    }
                }
                val files = listOf(
                    "summary.txt" to summary,
                    "bridge_diag.txt" to r.rawSnapshot,
                    "connection.log" to DiagLog.dump(),
                    "device.txt" to diagDeviceInfo(),
                    "settings.txt" to buildString {
                        appendLine("host = ${r.host}")
                        appendLine("slaveId = ${settings.modbusSlaveId}")
                        appendLine("pvReg = 0x${settings.modbusPvReg.toString(16)}")
                        appendLine("svReg = 0x${settings.modbusSvReg.toString(16)}")
                        appendLine("baud = ${settings.modbusBaud}")
                    },
                )
                packTextZip(files)
            }
            if (zip == null) {
                toast = L10n.get("diag.export_fail")
                return@launch
            }
            val name = "roastcurve-diag-${fileStamp()}.zip"
            val path = exportBackupToDownloads(name, zip)
            toast = if (path != null) L10n.get("diag.export_ok", "path" to path)
            else L10n.get("diag.export_fail")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(L10n.get("diag.title"), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(L10n.get("common.back2")) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            L10n.get("diag.warn_active"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // ===== 地址 + 开始 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(L10n.get("diag.host")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { runDiag() }, enabled = !running) {
                Text(if (running) L10n.get("diag.running") else if (report == null) L10n.get("diag.run") else L10n.get("diag.rerun"))
            }
        }

        if (running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // ===== 结论 =====
        report?.let { r ->
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                color = if (r.allPass) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        L10n.get("diag.verdict"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        r.verdict,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L10n.get("diag.advice"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(r.advice, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportPackage() }) { Text(L10n.get("diag.export")) }
                OutlinedButton(onClick = { runDiag() }, enabled = !running) { Text(L10n.get("diag.rerun")) }
            }
        }

        // ===== 步骤 =====
        Spacer(Modifier.height(12.dp))
        steps.forEach { step ->
            StepRow(
                step = step,
                expanded = expandedRaw == step.id,
                onToggle = { expandedRaw = if (expandedRaw == step.id) null else step.id },
            )
        }

        toast?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        // ===== 连接日志 =====
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.get("diag.log"), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { logTick++ }) { Text("↻") }
                TextButton(onClick = {
                    DiagLog.clear()
                    logTick++
                    toast = L10n.get("diag.clear_done")
                }) { Text(L10n.get("diag.clear")) }
            }
        }
        // logTick 触发重组后重新读取
        val entries = remember(logTick) { DiagLog.recent(80) }
        if (entries.isEmpty()) {
            Text(
                L10n.get("diag.log_empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    entries.reversed().forEach { e ->
                        Text(
                            "${e.level.tag} ${e.scope} ${e.message}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = true,
                            color = when (e.level) {
                                DiagLog.Level.ERROR -> MaterialTheme.colorScheme.error
                                DiagLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepRow(step: DiagStep, expanded: Boolean, onToggle: () -> Unit) {
    val color = when (step.status) {
        DiagStatus.PASS -> MaterialTheme.colorScheme.primary
        DiagStatus.FAIL -> MaterialTheme.colorScheme.error
        DiagStatus.SKIP -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.medium,   // 注意：主题里 small=胶囊（50%），不能当容器用
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onToggle() },
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    statusMark(step.status),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded && step.raw.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    step.raw,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusMark(s: DiagStatus): String = when (s) {
    DiagStatus.PASS -> "✓"
    DiagStatus.FAIL -> "✗"
    DiagStatus.SKIP -> "—"
}

private fun timestamp(): String {
    val dt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    fun p2(v: Int) = v.toString().padStart(2, '0')
    return "${dt.year}-${p2(dt.monthNumber)}-${p2(dt.dayOfMonth)} ${p2(dt.hour)}:${p2(dt.minute)}:${p2(dt.second)}"
}

private fun fileStamp(): String {
    val dt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    fun p2(v: Int) = v.toString().padStart(2, '0')
    return "${dt.year}${p2(dt.monthNumber)}${p2(dt.dayOfMonth)}-${p2(dt.hour)}${p2(dt.minute)}${p2(dt.second)}"
}
