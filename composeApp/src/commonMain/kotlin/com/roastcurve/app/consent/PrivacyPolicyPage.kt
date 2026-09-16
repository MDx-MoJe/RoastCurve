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

package com.roastcurve.app.consent

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roastcurve.app.platform.openUrl

/**
 * 应用内《隐私政策》阅读页。
 *
 * 常驻入口在设置页；首次启动的同意弹窗也可跳到这里。
 * 全文离线内置，不依赖网络；若配置了在线地址，额外提供浏览器打开入口。
 */
@Composable
fun PrivacyPolicyPage(onBack: () -> Unit) {
    val externalUrl = ConsentConfig.PRIVACY_URL_EXTERNAL
    val cnUrl = ConsentConfig.PRIVACY_URL_CN

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L10n.get("privacy.title"), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(L10n.get("ble.s2")) }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            L10n.get("privacy.updated"),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))

        // 正文按分节渲染：小标题加粗，正文常规
        SectionBody(L10n.get("privacy.intro"))

        Section(L10n.get("privacy.h1"), listOf(L10n.get("privacy.p1")))
        Section(L10n.get("privacy.h2"), listOf(L10n.get("privacy.p2")))
        Section(
            L10n.get("privacy.h3"),
            listOf(L10n.get("privacy.perms_intro")) +
                PERMISSION_KEYS.map { "· " + L10n.get(it) },
        )
        Section(L10n.get("privacy.h4"), listOf(L10n.get("privacy.p4")))
        Section(L10n.get("privacy.h5"), listOf(L10n.get("privacy.p5")))
        Section(L10n.get("privacy.h6"), listOf(L10n.get("privacy.p6")))
        Section(L10n.get("privacy.h7"), listOf(L10n.get("privacy.p7")))
        Section(L10n.get("privacy.h8"), listOf(L10n.get("privacy.p8")))

        // 在线版本入口（可选）
        if (externalUrl.isNotEmpty() || cnUrl.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                L10n.get("privacy.open_online"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            if (cnUrl.isNotEmpty()) {
                OutlinedButton(
                    onClick = { openUrl(cnUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(cnUrl, fontSize = 12.sp) }
                Spacer(Modifier.height(6.dp))
            }
            if (externalUrl.isNotEmpty()) {
                OutlinedButton(
                    onClick = { openUrl(externalUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(externalUrl, fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, paragraphs: List<String>) {
    Spacer(Modifier.height(18.dp))
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    paragraphs.forEach { p ->
        Text(
            p,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SectionBody(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
