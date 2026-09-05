package com.roastcurve.app.settings

import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.model.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roastcurve.shared.storage.SettingsStore
import kotlinx.coroutines.launch

/**
 * 温控器参数（Modbus）二级页：
 * 高风险参数（填错就读不到温度）收进独立页，避免设置页主屏误触。
 * 兼容其他品牌 Modbus 温控器：PV/SV 寄存器地址、波特率、从站地址。
 */
@Composable
fun ModbusConfigScreen(
    settings: Settings,
    onUpdate: (Settings) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L10n.get("modbus.s1"), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(L10n.get("common.back2")) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            L10n.get("modbus.s2"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // ===== 参数卡 =====
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                ModbusRegRow(
                    label = L10n.get("modbus.s3"),
                    value = settings.modbusPvReg,
                    format = { "0x" + it.toString(16).uppercase().padStart(4, '0') },
                    step = 1, min = 0, max = 0xFFFF,
                    onChange = { v -> settings.copy(modbusPvReg = v) }, onUpdate = onUpdate,
                )
                ModbusRegRow(
                    label = L10n.get("modbus.s4"),
                    value = settings.modbusSvReg,
                    format = { "0x" + it.toString(16).uppercase().padStart(4, '0') },
                    step = 1, min = 0, max = 0xFFFF,
                    onChange = { v -> settings.copy(modbusSvReg = v) }, onUpdate = onUpdate,
                )
                ModbusRegRow(
                    label = L10n.get("modbus.s5"),
                    value = settings.modbusBaud,
                    format = { it.toString() },
                    step = 100, min = 300, max = 115200,
                    onChange = { v -> settings.copy(modbusBaud = v) }, onUpdate = onUpdate,
                )
                ModbusRegRow(
                    label = L10n.get("modbus.s6"),
                    value = settings.modbusSlaveId,
                    format = { it.toString() },
                    step = 1, min = 1, max = 247,
                    onChange = { v -> settings.copy(modbusSlaveId = v) }, onUpdate = onUpdate,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            L10n.get("modbus.s7"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // ===== 恢复默认（台泉 TC4S）=====
        OutlinedButton(
            onClick = {
                val next = settings.copy(
                    modbusPvReg = 0x0000,
                    modbusSvReg = 0x0002,
                    modbusBaud = 1200,
                    modbusSlaveId = 1,
                )
                onUpdate(next)
                scope.launch { SettingsStore().save(next) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(L10n.get("modbus.s8")) }

        Spacer(Modifier.height(8.dp))
        Text(
            L10n.get("modbus.s9"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 参数行：label + [-] 值 [+] 步进编辑
 */
@Composable
private fun ModbusRegRow(
    label: String,
    value: Int,
    format: (Int) -> String,
    step: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Settings,
    onUpdate: (Settings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                val next = onChange((value - step).coerceAtLeast(min))
                onUpdate(next)
                scope.launch { SettingsStore().save(next) }
            },
            enabled = value - step >= min,
            modifier = Modifier.size(34.dp, 30.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("-", fontSize = 18.sp, modifier = Modifier.offset(y = (-2).dp)) }
        Text(
            format(value),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(88.dp),
        )
        OutlinedButton(
            onClick = {
                val next = onChange((value + step).coerceAtMost(max))
                onUpdate(next)
                scope.launch { SettingsStore().save(next) }
            },
            enabled = value + step <= max,
            modifier = Modifier.size(34.dp, 30.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("+", fontSize = 18.sp, modifier = Modifier.offset(y = (-1).dp)) }
    }
}