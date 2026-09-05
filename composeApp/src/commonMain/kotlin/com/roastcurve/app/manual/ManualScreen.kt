package com.roastcurve.app.manual

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 内置使用手册：结构化展示烘焙流程、事件术语、设置说明与注意事项。
 * 内容对应 docs/完整用户指导手册.md 的 App 操作部分，App 内随时可查。
 */
@Composable
fun ManualScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L10n.get("manual.title"), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(L10n.get("common.back2")) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Section(L10n.get("manual.s1")) {
                Bullet(L10n.get("manual.s2"))
                Bullet(L10n.get("manual.s3"))
                Bullet(L10n.get("manual.s4"))
                Bullet(L10n.get("manual.s5"))
            } }

            item { Section(L10n.get("manual.s6")) {
                Bullet(L10n.get("manual.s7"))
                Bullet(L10n.get("manual.s8"))
                Bullet(L10n.get("manual.s9"))
                Para(L10n.get("manual.s10"))
                Para(L10n.get("manual.s11"))
            } }

            item { Section(L10n.get("manual.s12")) {
                Step(L10n.get("manual.s13"), L10n.get("manual.s14"))
                Step(L10n.get("manual.s15"), L10n.get("manual.s16"))
                Step(L10n.get("manual.s17"), L10n.get("manual.s18"))
                Step(L10n.get("manual.s19"), L10n.get("manual.s20"))
                Step(L10n.get("manual.s21"), L10n.get("manual.s22"))
            } }

            item { Section(L10n.get("manual.s23")) {
                Bullet(L10n.get("manual.s24"))
                Bullet(L10n.get("manual.s25"))
                Bullet(L10n.get("manual.s26"))
                Para(L10n.get("manual.s27"))
            } }

            item { Section(L10n.get("manual.s28")) {
                Sub(L10n.get("manual.s29"))
                Bullet(L10n.get("manual.s30"))
                Sub(L10n.get("manual.s31"))
                Bullet(L10n.get("manual.s32"))
                Sub(L10n.get("manual.s33"))
                Bullet(L10n.get("manual.s34"))
            } }

            item { Section(L10n.get("manual.s35")) {
                SettingRow(L10n.get("manual.setting_autofollow"), L10n.get("manual.s36"))
                SettingRow(L10n.get("manual.setting_autoconnect"), L10n.get("manual.s37"))
                SettingRow(L10n.get("manual.setting_lookahead"), L10n.get("manual.setting_lookahead_desc"))
                SettingRow(L10n.get("manual.setting_darkmode"), L10n.get("manual.s38"))
            } }

            item { Section(L10n.get("manual.s70")) {
                Para(L10n.get("manual.s71"))
                Bullet(L10n.get("manual.s72"))
            } }

            item { Section(L10n.get("manual.s39")) {
                EventRow(L10n.get("manual.s40"), L10n.get("manual.s41"))
                EventRow(L10n.get("manual.s42"), L10n.get("manual.s43"))
                EventRow(L10n.get("manual.s44"), L10n.get("manual.s45"))
                EventRow(L10n.get("manual.s46"), L10n.get("manual.s47"))
                EventRow(L10n.get("manual.s48"), L10n.get("manual.s49"))
                EventRow(L10n.get("manual.s50"), L10n.get("manual.s51"))
                EventRow(L10n.get("manual.s52"), L10n.get("manual.s53"))
            } }

            item { Section(L10n.get("manual.s54")) {
                Bullet(L10n.get("manual.s55"))
                Bullet(L10n.get("manual.s56"))
                Bullet(L10n.get("manual.s57"))
                Bullet(L10n.get("manual.s58"))
                Bullet(L10n.get("manual.s59"))
            } }

            item { Section(L10n.get("manual.s60")) {
                Bullet(L10n.get("manual.s61"))
                Bullet("Copyright © 2026 MDx")
            } }
        }
    }
}

// ===== 内部渲染组件 =====

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Sub(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp)) {
        Text("· ", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Step(name: String, desc: String) {
    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingRow(name: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.6f))
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun EventRow(name: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
    }
    Spacer(Modifier.height(2.dp))
}
