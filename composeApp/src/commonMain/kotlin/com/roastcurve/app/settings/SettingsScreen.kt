package com.roastcurve.app.settings

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.clickable
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
            Text(L10n.get("settings.s1"), style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = onOpenManual) { Text(L10n.get("settings.s2")) }
                OutlinedButton(onClick = onBack) { Text(L10n.get("common.back2")) }
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
                    statusText = L10n.get("settings.s3")
                } else {
                    try {
                        val json = unpackBackupZip(data)
                            ?: throw IllegalStateException(L10n.get("settings.s4"))
                        pendingImport = BackupCodec.decode(json)
                    } catch (e: Exception) {
                        statusText = L10n.get("settings.s5", "take" to (e.message?.take(40) ?: ""))
                    }
                }
            }
            onDispose { BackupBridge.onPicked = null }
        }

        Text(L10n.get("settings.s6"), style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    L10n.get("settings.s7"),
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
                                    // zip 铁律：统一 zip 直写 Download（shareText 在 vivo 上会静默失败）
                                    val zip = packBackupZip(fname, BackupCodec.encode(bundle))
                                    val zipName = fname.removeSuffix(".json") + ".zip"
                                    val where = if (zip != null) exportBackupToDownloads(zipName, zip) else null
                                    statusText = where?.let { L10n.get("settings.s14", "where" to where) }
                                        ?: "此系统不支持直存，请用「存到下载目录」按钮"
                                } catch (e: Exception) {
                                    statusText = L10n.get("settings.s9", "take" to (e.message?.take(50) ?: ""))
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy,
                    ) { Text(L10n.get("settings.export_zip")) }
                    OutlinedButton(
                        onClick = { BackupBridge.requestPick?.invoke() },
                        enabled = !busy,
                    ) { Text(L10n.get("settings.s11")) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    L10n.get("settings.s12", "appVersion" to AppDirs.appVersion) +
                        AppDirs.buildIdentityLabel.let { if (it.isNotBlank()) " ($it)" else "" },
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
                                    statusText = L10n.get("settings.s13")
                                    busy = false
                                    return@launch
                                }
                                val zipName = fname.removeSuffix(".json") + ".zip"
                                val where = exportBackupToDownloads(zipName, zip)
                                statusText = where?.let { L10n.get("settings.s14", "where" to where) }
                                    ?: L10n.get("settings.s13")
                            } catch (e: Exception) {
                                statusText = L10n.get("settings.s9", "take" to (e.message?.take(50) ?: ""))
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(L10n.get("settings.s15")) }
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
                title = { Text(L10n.get("settings.s16")) },
                text = {
                    Text(L10n.get("settings.s17", "size" to b.records.size, "size2" to b.profiles.size) +
                         (if (b.settings != null) L10n.get("settings.s18") else "") +
                         L10n.get("settings.s19"))
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
                                statusText = L10n.get("settings.s20", "size" to bundle.records.size, "size2" to bundle.profiles.size)
                            } catch (e: Exception) {
                                statusText = L10n.get("settings.s21", "take" to (e.message?.take(40) ?: ""))
                            } finally { busy = false }
                        }
                    }) { Text(L10n.get("settings.s22"), color = MaterialTheme.colorScheme.primary) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingImport = null }) { Text(L10n.get("settings.s23")) }
                },
            )
        }

        // ===== 语言 =====
        Text(com.roastcurve.shared.l10n.L10n.get("settings.language_section"), style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        var langEpoch by remember { mutableStateOf(0) }
        key(langEpoch) {
            LanguageCard(onApply = { langEpoch++ })
        }
        Spacer(Modifier.height(16.dp))

        // ===== 跟随控制 =====
        Text(L10n.get("settings.s24"), style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(L10n.get("settings.s25"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        L10n.get("settings.s26"),
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
                    Text(L10n.get("settings.s27"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        L10n.get("settings.s28"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            val v = (settings.lookaheadSec - 1).coerceAtLeast(0)
                            val next = settings.copy(lookaheadSec = v)
                            onUpdate(next); scope.launch { SettingsStore().save(next) }
                        },
                    ) { Text("−1") }
                    var editingLookahead by remember { mutableStateOf(false) }
                    var draftText by remember { mutableStateOf("") }
                    if (editingLookahead) {
                        OutlinedTextField(
                            value = draftText,
                            onValueChange = { draftText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("秒") },
                            singleLine = true,
                            modifier = Modifier.width(90.dp).padding(horizontal = 4.dp),
                            textStyle = MaterialTheme.typography.titleMedium,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        )
                        TextButton(onClick = {
                            val v = draftText.toIntOrNull()?.coerceIn(0, 120)
                            if (v != null) {
                                val next = settings.copy(lookaheadSec = v)
                                onUpdate(next); scope.launch { SettingsStore().save(next) }
                            }
                            editingLookahead = false
                        }) { Text("OK") }
                    } else {
                        Text(
                            "${settings.lookaheadSec}s",
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clickable {
                                    draftText = settings.lookaheadSec.toString()
                                    editingLookahead = true
                                },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val v = (settings.lookaheadSec + 1).coerceAtMost(120)
                            val next = settings.copy(lookaheadSec = v)
                            onUpdate(next); scope.launch { SettingsStore().save(next) }
                        },
                    ) { Text("+1") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 自动风速下限 =====
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(L10n.get("settings.s43"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        L10n.get("settings.s44"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            val v = (settings.fanAutoFloorPct - 5).coerceAtLeast(7)
                            val next = settings.copy(fanAutoFloorPct = v)
                            onUpdate(next); scope.launch { SettingsStore().save(next) }
                        },
                    ) { Text("−5") }
                    var editingFanFloor by remember { mutableStateOf(false) }
                    var fanFloorDraft by remember { mutableStateOf("") }
                    if (editingFanFloor) {
                        OutlinedTextField(
                            value = fanFloorDraft,
                            onValueChange = { fanFloorDraft = it.filter { ch -> ch.isDigit() } },
                            label = { Text("%") },
                            singleLine = true,
                            modifier = Modifier.width(90.dp).padding(horizontal = 4.dp),
                            textStyle = MaterialTheme.typography.titleMedium,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        )
                        TextButton(onClick = {
                            val v = fanFloorDraft.toIntOrNull()?.coerceIn(7, 60)
                            if (v != null) {
                                val next = settings.copy(fanAutoFloorPct = v)
                                onUpdate(next); scope.launch { SettingsStore().save(next) }
                            }
                            editingFanFloor = false
                        }) { Text("OK") }
                    } else {
                        Text(
                            "${settings.fanAutoFloorPct}%",
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clickable {
                                    fanFloorDraft = settings.fanAutoFloorPct.toString()
                                    editingFanFloor = true
                                },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val v = (settings.fanAutoFloorPct + 5).coerceAtMost(60)
                            val next = settings.copy(fanAutoFloorPct = v)
                            onUpdate(next); scope.launch { SettingsStore().save(next) }
                        },
                    ) { Text("+5") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 设备连接 =====
        Text(L10n.get("settings.s29"), style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(L10n.get("settings.s30"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        L10n.get("settings.s31"),
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
                Text(L10n.get("settings.s32"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    L10n.get("settings.s33"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ===== 烘焙事件 =====
        Text(L10n.get("settings.s34"), style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(L10n.get("settings.s35"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        L10n.get("settings.s36"),
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
            L10n.get("settings.s37"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text(L10n.get("settings.s38"), style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            onClick = { openUrl("https://afdian.com/a/RoastCurve") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(L10n.get("settings.s39"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    L10n.get("settings.s40"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 姐妹应用入口（点击打开豆袋 GitHub 仓库）
        Spacer(Modifier.height(6.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            onClick = { openUrl("https://github.com/MDx-MoJe/CoffeeBeanTracker") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(L10n.get("settings.s41"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    L10n.get("settings.s42"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

