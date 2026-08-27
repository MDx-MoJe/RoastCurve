package com.roastcurve.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roastcurve.shared.protocol.BleConfigDevice
import com.roastcurve.shared.protocol.bleConfigure
import com.roastcurve.shared.protocol.bleScanConfigDevices
import kotlinx.coroutines.launch

/**
 * 蓝牙配网：扫描桥接器 BLE 设备，填入 WiFi 凭据，通过 BLE 发送。
 * 适用场景：桥接器处于配网模式（紫灯闪烁 / 首次上电 / 连续连不上 WiFi）。
 */
@Composable
fun BleConfigScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<BleConfigDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<BleConfigDevice?>(null) }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var configuring by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("蓝牙配网", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "先让桥接器进入配网模式（紫灯闪烁），再扫描连接。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scanning = true
                devices = emptyList()
                selected = null
                message = null
                scope.launch {
                    val found = bleScanConfigDevices(8000)
                    devices = found
                    scanning = false
                    if (found.isEmpty()) {
                        message = "未找到桥接器，确认它紫灯闪烁（配网模式）且手机蓝牙已开"
                    }
                }
            },
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (scanning) "扫描中…（约 8 秒）" else "扫描桥接器")
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("找到的设备（点选）：", style = MaterialTheme.typography.labelMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(devices, key = { it.address }) { d ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = if (selected == d) 4.dp else 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { selected = d },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge)
                            Text(d.address, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text("WiFi 名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("WiFi 密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val dev = selected
                if (dev == null) {
                    message = "请先扫描并选择一个设备"
                    return@Button
                }
                if (ssid.isBlank() || pass.length < 8) {
                    message = "WiFi 名不能为空，密码至少 8 位"
                    return@Button
                }
                configuring = true
                message = null
                scope.launch {
                    val ok = bleConfigure(dev.address, ssid.trim(), pass)
                    configuring = false
                    message = if (ok) {
                        "已发送，桥接器正在连接 $ssid，稍后自动重启。"
                    } else {
                        "配网失败，请重试（确认桥接器在配网模式）"
                    }
                }
            },
            enabled = !configuring && !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (configuring) "配网中…" else "配网")
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.primary)
        }
    }
}
