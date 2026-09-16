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

package com.roastcurve.app.settings

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roastcurve.app.platform.BlePermissionBridge
import com.roastcurve.shared.protocol.BleConfigDevice
import com.roastcurve.shared.protocol.bleConfigure
import com.roastcurve.shared.protocol.bleScanConfigDevices
import com.roastcurve.shared.protocol.discoverBridge
import com.roastcurve.shared.protocol.SignalProbe
import com.roastcurve.shared.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var resetting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // 蓝牙权限：先弹用途说明，用户点「允许」后再走系统授权
    var showPermRationale by remember { mutableStateOf(false) }
    var pendingScan by remember { mutableStateOf<(() -> Unit)?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(L10n.get("ble.s1"), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(L10n.get("ble.s2")) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            L10n.get("ble.s3"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 换 WiFi 一键重置：桥接器正常联网时，连上它发 /reset，让它清凭据重启进配网
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                resetting = true
                message = null
                scope.launch {
                    // 读上次连过的桥接器 IP（若没连过则提示先连接）
                    val host = withContext(Dispatchers.IO) {
                        runCatching { SettingsStore().load().lastBridgeHost }.getOrNull() ?: ""
                    }
                    if (host.isBlank()) {
                        message = L10n.get("ble.s4")
                    } else {
                        val ok = SignalProbe.resetWifi(host)
                        message = if (ok) {
                            L10n.get("ble.s5")
                        } else {
                            L10n.get("ble.s6", "host" to host)
                        }
                    }
                    resetting = false
                }
            },
            enabled = !resetting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (resetting) L10n.get("ble.s7") else L10n.get("ble.s8"))
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                devices = emptyList()
                selected = null
                message = null

                // 权限前置告知：蓝牙配网需要扫描附近设备。
                // Android 12+ 为「附近的设备」，11 及以下系统机制上显示为「位置信息」——
                // 本应用仅用其发现桥接器，不读取、不存储、不上传位置。
                val doScan: () -> Unit = {
                    scanning = true
                    scope.launch {
                        val found = bleScanConfigDevices(8000)
                        devices = found
                        scanning = false
                        if (found.isEmpty()) {
                            message = L10n.get("ble.s9")
                        }
                    }
                    Unit
                }

                val alreadyGranted = BlePermissionBridge.granted?.invoke() ?: true
                if (alreadyGranted) {
                    doScan()
                } else {
                    showPermRationale = true
                    pendingScan = doScan
                }
            },
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (scanning) L10n.get("ble.s10") else L10n.get("ble.s11"))
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(L10n.get("ble.s12"), style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                devices.forEach { d ->
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
            label = { Text(L10n.get("ble.s13")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text(L10n.get("ble.s14")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val dev = selected
                if (dev == null) {
                    message = L10n.get("ble.s15")
                    return@Button
                }
                if (ssid.isBlank() || pass.length < 8) {
                    message = L10n.get("ble.s16")
                    return@Button
                }
                configuring = true
                message = null
                scope.launch {
                    val ok = bleConfigure(dev.address, ssid.trim(), pass)
                    configuring = false
                    if (ok) {
                        // 桥接器收到凭据后重启并连 WiFi（需几秒），用 mDNS 自动发现 IP
                        message = L10n.get("ble.s17", "ssid" to ssid)
                        val bridge = discoverBridge(20000)
                        if (bridge != null) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val s = SettingsStore()
                                    s.save(s.load().copy(lastBridgeHost = bridge.host))
                                }
                            }
                            message = L10n.get("ble.s18", "host" to bridge.host)
                        } else {
                            message = L10n.get("ble.s19")
                        }
                    } else {
                        message = L10n.get("ble.s20")
                    }
                }
            },
            enabled = !configuring && !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (configuring) L10n.get("ble.s21") else L10n.get("ble.s22"))
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.primary)
        }
    }

    // 权限用途告知：申请前先把「为什么需要、用来干什么」讲清楚
    if (showPermRationale) {
        AlertDialog(
            onDismissRequest = { showPermRationale = false; pendingScan = null },
            title = { Text(L10n.get("ble.perm.title")) },
            text = { Text(L10n.get("ble.perm.body")) },
            confirmButton = {
                TextButton(onClick = {
                    showPermRationale = false
                    val scan = pendingScan
                    pendingScan = null
                    BlePermissionBridge.onResult = null
                    BlePermissionBridge.request?.invoke()
                    BlePermissionBridge.onResult = { ok -> if (ok) scan?.invoke() }
                }) { Text(L10n.get("ble.perm.allow")) }
            },
            dismissButton = {
                TextButton(onClick = { showPermRationale = false; pendingScan = null }) {
                    Text(L10n.get("ble.perm.deny"))
                }
            },
        )
    }
}
