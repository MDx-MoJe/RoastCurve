package com.roastcurve.app.settings

import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.model.Settings
import com.roastcurve.shared.protocol.GpioConfigClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 桥接器 GPIO 引脚配置二级页（固件 v1.8.1+ /gpiocfg 端点）
 * DIY 换板不换线：RS485 TX/RX + 风机 PWM 三脚可配，保存后需重启桥接器生效。
 * 数据源是桥接器本身（HTTP 8898），不是本地设置。
 */
@Composable
fun GpioConfigScreen(
    settings: Settings,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val host = settings.lastBridgeHost
    val client = remember(host) { if (host.isNotBlank()) GpioConfigClient(host) else null }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var firmwareVersion by remember { mutableStateOf<String?>(null) }  // 探测到的固件版本
    var oldFirmware by remember { mutableStateOf(false) }              // true=固件太旧需升级
    var pinTx by remember { mutableStateOf(17) }
    var pinRx by remember { mutableStateOf(18) }
    var pinFan by remember { mutableStateOf(2) }
    var pool by remember { mutableStateOf<List<Int>>(emptyList()) }
    var saved by remember { mutableStateOf(false) }

    /** 版本号比较："1.8.3" >= "1.8.1" → true（按 . 分段数字比） */
    fun versionAtLeast(v: String?, min: String): Boolean {
        if (v == null) return false
        val a = v.split('.').mapNotNull { it.toIntOrNull() }
        val b = min.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    /** 探测 + 拉配置的统一入口（进入页与重试共用；suspend，调用方负责 loading） */
    suspend fun reload() {
        error = null; oldFirmware = false
        val (ver, reachable) = client?.probe() ?: (null to false)
        firmwareVersion = ver
        when {
            !reachable -> error = L10n.get("gpio.err_conn")                    // HTTP 不通 = 连不上
            ver == null || !versionAtLeast(ver, "1.8.1") -> oldFirmware = true // 极旧/旧固件
            else -> {
                val cfg = client?.fetch()
                if (cfg == null) { error = L10n.get("gpio.err_fetch") }
                else { pinTx = cfg.pinTx; pinRx = cfg.pinRx; pinFan = cfg.pinFan; pool = cfg.pool }
            }
        }
    }

    // 进入：先探测桥接器（版本 + 连通）→ 够新才拉配置；旧固件给明确升级引导
    LaunchedEffect(host) {
        if (host.isBlank()) {
            error = L10n.get("gpio.err_host")
            loading = false
            return@LaunchedEffect
        }
        loading = true
        reload()
        loading = false
    }

    fun save() {
        val c = client ?: return
        scope.launch {
            val ok = c.save(pinTx, pinRx, pinFan)
            saved = true
            if (!ok) error = L10n.get("gpio.err_save")
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L10n.get("gpio.title"), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(L10n.get("common.back2")) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            L10n.get("gpio.sub"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        when {
            loading -> {
                CircularProgressIndicator(Modifier.padding(top = 32.dp))
            }
            error != null -> {
                Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                OutlinedButton(onClick = {
                    error = null; oldFirmware = false; loading = true
                    scope.launch {
                        reload()
                        loading = false
                    }
                }) { Text(L10n.get("gpio.retry")) }
            }
            oldFirmware -> {
                // 固件太旧：明确升级引导（GPIO 可配是 v1.8.1+ 的 /gpiocfg 端点）
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            L10n.get("gpio.upgrade_title"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            L10n.get("gpio.upgrade_body", "cur" to (firmwareVersion ?: L10n.get("gpio.ver_unknown"))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    L10n.get("gpio.upgrade_step"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    error = null; oldFirmware = false; loading = true
                    scope.launch {
                        reload()
                        loading = false
                    }
                }) { Text(L10n.get("gpio.retry")) }
            }
            else -> {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        GpioPinRow(
                            label = L10n.get("gpio.tx"),
                            value = pinTx,
                            pool = pool,
                            taken = { p -> p == pinRx || p == pinFan },
                            onChange = { pinTx = it },
                        )
                        GpioPinRow(
                            label = L10n.get("gpio.rx"),
                            value = pinRx,
                            pool = pool,
                            taken = { p -> p == pinTx || p == pinFan },
                            onChange = { pinRx = it },
                        )
                        GpioPinRow(
                            label = L10n.get("gpio.fan"),
                            value = pinFan,
                            pool = pool,
                            taken = { p -> p == pinTx || p == pinRx },
                            onChange = { pinFan = it },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    L10n.get("gpio.note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (saved) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L10n.get("gpio.saved"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = ::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pinTx != pinRx && pinTx != pinFan && pinRx != pinFan,
                ) {
                    Text(L10n.get("gpio.save"))
                }
            }
        }
    }
}

/** 一行：标签 + 下拉选择引脚（已占用项置灰） */
@Composable
private fun GpioPinRow(
    label: String,
    value: Int,
    pool: List<Int>,
    taken: (Int) -> Boolean,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val options = if (pool.contains(value)) pool else listOf(value) + pool
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text("GPIO $value", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("GPIO $p" + (if (p == value) "  ✓" else "")) },
                        onClick = { onChange(p); expanded = false },
                        enabled = p == value || !taken(p),
                    )
                }
            }
        }
    }
}
