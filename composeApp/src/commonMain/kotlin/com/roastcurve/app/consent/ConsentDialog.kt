package com.roastcurve.app.consent

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次启动 / 政策更新时的隐私政策同意弹窗。
 *
 * - 同意前遮蔽全部内容（不同意即无法使用）
 * - 拒绝时弹二次确认，确认后退出应用（走系统返回/finish 语义由平台层处理，
 *   这里通过 onDeclineForever 回调通知外层）
 * - 在线链接入口按 ConsentConfig 配置动态显示（预留给内外网分发）
 */
@Composable
fun ConsentDialog(
    onAccept: () -> Unit,
    onDeclineForever: () -> Unit,
) {
    var showDeclineConfirm by remember { mutableStateOf(false) }

    val externalUrl = ConsentConfig.PRIVACY_URL_EXTERNAL
    val cnUrl = ConsentConfig.PRIVACY_URL_CN

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        L10n.get("consent.s1"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))

                    // 内置政策全文，可滚动；在线链接若配置则附在文末
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            PRIVACY_POLICY_TEXT,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (externalUrl.isNotEmpty() || cnUrl.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                buildString {
                                    if (externalUrl.isNotEmpty()) append(L10n.get("consent.s2", "externalUrl" to ""))
                                    if (cnUrl.isNotEmpty()) append(L10n.get("consent.s3", "cnUrl" to ""))
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { showDeclineConfirm = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(50),
                        ) { Text(L10n.get("consent.s4")) }
                        Button(
                            onClick = onAccept,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(50),
                        ) { Text(L10n.get("consent.s5")) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L10n.get("consent.s6"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }

            // 拒绝后的二次确认弹窗
            if (showDeclineConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeclineConfirm = false },
                    title = { Text(L10n.get("consent.s7")) },
                    text = { Text(L10n.get("consent.s8")) },
                    confirmButton = {
                        TextButton(onClick = onDeclineForever) { Text(L10n.get("consent.s9"), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeclineConfirm = false }) { Text(L10n.get("consent.s10")) }
                    },
                )
            }
        }
    }
}
