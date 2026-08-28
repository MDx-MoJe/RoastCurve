package com.roastcurve.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.l10n.LangPackIO
import com.roastcurve.shared.l10n.langPackIO
import kotlinx.coroutines.launch

/**
 * 语言卡：内置中英切换 + 语言包导入/导出 + 已存包加载
 * 导入用 zip/json，回退链：语言包 → 内置 EN → 中文源
 */
@Composable
internal fun LanguageCard(
    onApply: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val io: LangPackIO = remember { langPackIO() }
    val state by L10n.state.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var savedPacks by remember { mutableStateOf<List<String>>(emptyList()) }
    var filePick by remember { mutableStateOf(false) }
    // 待应用的选择（点了 chip 先记着，点「应用」才真正切换）
    var pendingBuiltin by remember { mutableStateOf<L10n.BuiltinLang?>(null) }
    var pendingPackFile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { savedPacks = io.listSaved() }

    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("当前语言", style = MaterialTheme.typography.bodyLarge)
            Text(
                state.displayName + if (state.packCount > 0) "（${state.packCount} 词条）" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = pendingBuiltin?.let { it == L10n.BuiltinLang.ZH } ?: (state.pack == null && state.builtin == L10n.BuiltinLang.ZH),
                    onClick = { pendingBuiltin = L10n.BuiltinLang.ZH; pendingPackFile = null; status = null },
                    label = { Text("中文") },
                )
                FilterChip(
                    selected = pendingBuiltin?.let { it == L10n.BuiltinLang.EN } ?: (state.pack == null && state.builtin == L10n.BuiltinLang.EN),
                    onClick = { pendingBuiltin = L10n.BuiltinLang.EN; pendingPackFile = null; status = null },
                    label = { Text("English") },
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { filePick = true }, enabled = io.let { true }) {
                    Text("导入语言包")
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        val zip = io.exportCurrent()
                        status = if (zip != null) "✅ 语言包已生成（见导出分享）" else "导出失败"
                    }
                }) { Text("导出当前") }
                // 应用：写状态 + 通知上层重建 UI
                Button(
                    enabled = pendingBuiltin != null || pendingPackFile != null,
                    onClick = {
                        pendingPackFile?.let { pf ->
                            scope.launch {
                                io.loadSaved(pf).onSuccess { name ->
                                    status = "✅ $name"
                                    // 持久化语言包选择
                                    val store = com.roastcurve.shared.storage.SettingsStore()
                                    val cur = store.load()
                                    store.save(cur.copy(langPackFile = pf, langBuiltin = "zh-CN"))
                                    onApply()
                                }
                                pendingPackFile = null
                            }
                        } ?: pendingBuiltin?.let { lang ->
                            L10n.selectBuiltin(lang)
                            // 持久化内置语言选择
                            scope.launch {
                                val store = com.roastcurve.shared.storage.SettingsStore()
                                val cur = store.load()
                                store.save(cur.copy(langBuiltin = lang.code, langPackFile = ""))
                            }
                            status = null
                            pendingBuiltin = null
                            onApply()
                        }
                    },
                ) { Text("应用") }
            }

            // 已保存语言包
            if (savedPacks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("已保存的语言包", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                savedPacks.forEach { name ->
                    TextButton(onClick = {
                        pendingPackFile = name; pendingBuiltin = null; status = null
                    }) { Text(name, style = MaterialTheme.typography.bodySmall) }
                }
            }

            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // 平台文件选择（Android 侧由 BackupBridge 式桥接提供；此处用平台钩子）
    if (filePick) {
        // 简化：直接调平台 picker（expect/actual 在 androidMain 用 ActivityResult）
        LaunchedEffect(Unit) {
            filePick = false
            pickLangFile { bytes ->
                if (bytes != null) {
                    scope.launch {
                        io.importFrom(bytes).fold(
                            onSuccess = { name ->
                                status = "✅ 已应用语言包：$name"
                                savedPacks = io.listSaved()
                            },
                            onFailure = { status = "❌ ${it.message?.take(40)}" },
                        )
                    }
                }
            }
        }
    }
}
