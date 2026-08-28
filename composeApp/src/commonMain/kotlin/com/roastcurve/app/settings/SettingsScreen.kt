package com.roastcurve.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roastcurve.shared.AppDirs
import com.roastcurve.shared.model.Settings
import com.roastcurve.app.history.exportBackupToDownloads
import com.roastcurve.app.history.packBackupZip
import com.roastcurve.app.history.shareText
import com.roastcurve.app.history.unpackBackupZip
import com.roastcurve.app.platform.openUrl
import com.roastcurve.shared.BackupBridge
import com.roastcurve.shared.model.BackupBundle
import com.roastcurve.shared.storage.ProfileStore
import com.roastcurve.shared.storage.RoastStore
import com.roastcurve.shared.storage.BackupCodec
import com.roastcurve.shared.storage.SettingsStore
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

/**
 * 设置页
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onUpdate: (Settings) -> Unit,
    onBack: () -> Unit,
    onOpenManual: () -> Unit = {},
    onOpenBleConfig: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("设置", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = onOpenManual) { Text("使用手册") }
                OutlinedButton(onClick = onBack) { Text("返回") }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ===== 数据备份 =====
        var busy by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf<String?>(null) }
        var pendingImport by remember { mutableStateOf<BackupBundle?>(null) }

        // 注册文件选择结果回调（MainActivity onActivityResult 回带文本）
        DisposableEffect(Unit) {
            BackupBridge.onPicked = { data, _ ->
                if (data == null) {
                    statusText = "已取消导入"
                } else {
                    try {
                        val json = unpackBackupZip(data)
                            ?: throw IllegalStateException("无法识别的备份格式")
                        pendingImport = BackupCodec.decode(json)
                    } catch (e: Exception) {
                        statusText = "解析失败：${e.message?.take(50)}"
                    }
                }
            }
            onDispose { BackupBridge.onPicked = null }
        }

        Text("数据备份", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "备份全部记录、模板与设置。「存到下载目录」生成 .zip；「导出到文件」分享 .json。导入时两种格式通用，按 ID 合并（同 ID 覆盖、不删除现有数据）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            scope.launch {
                                try {
                                    val bundle = BackupBundle(
                                        records = RoastStore().listAll(),
                                        profiles = ProfileStore().listAll(),
                                        settings = SettingsStore().load(),
                                    )
                                    val d = kotlinx.datetime.Clock.System.now()
                                        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                                    fun p2(v: Int) = v.toString().padStart(2, '0')
                                    val fname = "roastcurve_backup_${p2(d.monthNumber)}${p2(d.dayOfMonth)}-${p2(d.hour)}${p2(d.minute)}.json"
                                    shareText(fname, BackupCodec.encode(bundle))
                                    statusText = "已调起分享：${bundle.records.size} 条记录 / ${bundle.profiles.size} 个模板"
                                } catch (e: Exception) {
                                    statusText = "导出失败：${e.message?.take(50)}"
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy,
                    ) { Text("导出 .json") }
                    OutlinedButton(
                        onClick = { BackupBridge.requestPick?.invoke() },
                        enabled = !busy,
                    ) { Text("从文件导入") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前版本：${AppDirs.appVersion}" +
                        AppDirs.buildIdentityLabel.let { if (it.isNotBlank()) "（$it）" else "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        if (busy) return@OutlinedButton
                        busy = true
                        scope.launch {
                            try {
                                val bundle = BackupBundle(
                                    records = RoastStore().listAll(),
                                    profiles = ProfileStore().listAll(),
                                    settings = SettingsStore().load(),
                                )
                                val d = kotlinx.datetime.Clock.System.now()
                                    .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                                fun p2(v: Int) = v.toString().padStart(2, '0')
                                val fname = "roastcurve_backup_${p2(d.monthNumber)}${p2(d.dayOfMonth)}-${p2(d.hour)}${p2(d.minute)}.json"
                                val json = BackupCodec.encode(bundle)
                                val zip = packBackupZip(fname, json)
                                if (zip == null) {
                                    statusText = "此系统不支持直存，请用「导出到文件」分享保存"
                                    busy = false
                                    return@launch
                                }
                                val zipName = fname.removeSuffix(".json") + ".zip"
                                val where = exportBackupToDownloads(zipName, zip)
                                statusText = where?.let { "已保存到 $where" }
                                    ?: "此系统不支持直存，请用「导出到文件」分享保存"
                            } catch (e: Exception) {
                                statusText = "导出失败：${e.message?.take(50)}"
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("存到下载目录（同局域网迁移推荐）") }
                statusText?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 导入确认
        pendingImport?.let { b ->
            AlertDialog(
                onDismissRequest = { pendingImport = null },
                title = { Text("确认导入？") },
                text = {
                    Text("将合并导入 ${b.records.size} 条记录、${b.profiles.size} 个模板" +
                         (if (b.settings != null) "及设置项" else "") +
                         "。同 ID 覆盖、现有数据保留。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        val bundle = b
                        pendingImport = null
                        busy = true
                        scope.launch {
                            try {
                                bundle.records.forEach { RoastStore().save(it) }
                                bundle.profiles.forEach { ProfileStore().save(it) }
                                bundle.settings?.let {
                                    onUpdate(it)
                                    SettingsStore().save(it)
                                }
                                statusText = "✅ 导入完成：${bundle.records.size} 条记录 / ${bundle.profiles.size} 个模板"
                            } catch (e: Exception) {
                                statusText = "导入失败：${e.message?.take(50)}"
                            } finally { busy = false }
                        }
                    }) { Text("导入", color = MaterialTheme.colorScheme.primary) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingImport = null }) { Text("取消") }
                },
            )
        }

        // ===== 跟随控制 =====
        Text("跟随控制", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("入豆自动开始跟随", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "点「入豆」时若已选模板，自动进入跟随曲线模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoFollowOnCharge,
                    onCheckedChange = { v ->
                        val next = settings.copy(autoFollowOnCharge = v)
                        onUpdate(next)
                        scope.launch { SettingsStore().save(next) }
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ===== 跟随前瞻 =====
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("跟随前瞻", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "SV 提前参考 N 秒后的目标值，补偿炉子热惯性（0=关闭）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            val v = (settings.lookaheadSec - 5).coerceAtLeast(0)
                            val next = settings.copy(lookaheadSec = v)
                            onUpdate(next); scope.launch { SettingsStore().save(next) }
                        },
                    ) { Text("−5s") }
                    Text(
                        "${settings.lookaheadSec}s",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(
                        onClick = {
                            val v = (settings.lookaheadSec + 5).coerceAtMost(60)
                            val next = settings.copy(lookaheadSec = v)
                            onUpdate(next); scope.launch { SettingsStore().save(next) }
                        },
                    ) { Text("+5s") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 设备连接 =====
        Text("设备连接", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启动时自动连接", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "启动应用后自动连接上次的桥接器 IP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoConnectOnLaunch,
                    onCheckedChange = { v ->
                        val next = settings.copy(autoConnectOnLaunch = v)
                        onUpdate(next)
                        scope.launch { SettingsStore().save(next) }
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // 蓝牙配网入口
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            onClick = onOpenBleConfig,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("🔵 蓝牙配网", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "给桥接器设置 WiFi（无需电脑），配网模式下手机蓝牙直连",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ===== 烘焙事件 =====
        Text("烘焙事件", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("深烘模式（显示二爆）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "底部事件条增加 二爆/二爆止，意式深烘用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.showSecondCrack,
                    onCheckedChange = { v ->
                        val next = settings.copy(showSecondCrack = v)
                        onUpdate(next)
                        scope.launch { SettingsStore().save(next) }
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "提示：建议烘焙前先在监控页选好模板（选模板不会立刻开始跟随），投豆瞬间点「入豆」即自动跟随。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text("支持开发者", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            onClick = { openUrl("https://afdian.com/a/RoastCurve") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("☕ 支持开发者", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "烤豆永久免费、开源、无广告。如果你觉得它帮到了你，欢迎到爱发电支持。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

