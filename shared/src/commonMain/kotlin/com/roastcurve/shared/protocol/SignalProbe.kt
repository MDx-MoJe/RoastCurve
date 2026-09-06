package com.roastcurve.shared.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桥接器状态探测：读取固件开的 HTTP 状态口 /status（端口 8898）。
 * 用裸 TCP 手写 GET，避免引入额外 HTTP 客户端依赖。
 * 字段对应固件 handleStatus 的 JSON：rssi/uptime/client/fan_speed/ver/sim/holder/safe/sv 等。
 */
data class BridgeStatus(
    val rssi: Int? = null,
    val safe: Boolean? = null,     // 固件安全模式激活（看门狗保温中）
    val sv: Int? = null,           // 固件缓存的当前 SV 设定
    val fanSpeed: Int? = null,
    val ver: String? = null,
)

object SignalProbe {

    /** 单次探测完整状态；不可达/超时返回 null */
    suspend fun fetchStatus(host: String): BridgeStatus? = withContext(Dispatchers.IO) {
        val t = TcpByteTransport(host = host, port = 8898, readTimeoutMs = 1500L)
        withTimeoutOrNull(2500L) {
            try {
                t.open()
                t.write("GET /status HTTP/1.0\r\nHost: $host\r\n\r\n".encodeToByteArray())
                val buf = StringBuilder()
                while (true) {
                    val one = t.readExact(1) ?: break
                    buf.append(one[0].toInt().toChar())
                    if (buf.length > 512) break
                }
                parseStatus(buf.toString())
            } catch (_: Exception) {
                null
            } finally {
                try { t.close() } catch (_: Exception) {}
            }
        }
    }

    /** 解析 /status JSON（裸字符串提取，避免引入 JSON 解析依赖） */
    private fun parseStatus(body: String): BridgeStatus? {
        if (body.indexOf("\"rssi\":") < 0) return null
        fun field(name: String): String? {
            val idx = body.indexOf("\"$name\":")
            if (idx < 0) return null
            val rest = body.substring(idx + name.length + 3)
            // 数字或带引号字符串
            val c = rest.firstOrNull() ?: return null
            return if (c == '"') rest.drop(1).takeWhile { it != '"' }
            else rest.takeWhile { it.isDigit() || it == '-' }
        }
        return BridgeStatus(
            rssi = field("rssi")?.toIntOrNull(),
            safe = field("safe")?.let { it == "1" || it == "true" },
            sv = field("sv")?.toIntOrNull(),
            fanSpeed = field("fan_speed")?.toIntOrNull(),
            ver = field("ver"),
        )
    }

    /** 兼容旧接口：只取 RSSI（dBm）或 null */
    suspend fun fetchRssi(host: String): Int? = fetchStatus(host)?.rssi

    /**
     * 重置桥接器 WiFi：访问 /reset 端点，固件会清除凭据并重启进配网模式。
     * 用于换 WiFi 时一键重新配网（烘豆机里按不到 BOOT 键）。
     * @return 是否成功发出重置命令（固件会在收到后重启，连接随即断开）
     */
    suspend fun resetWifi(host: String): Boolean = withContext(Dispatchers.IO) {
        val t = TcpByteTransport(host = host, port = 8898, readTimeoutMs = 1500L)
        withTimeoutOrNull(2500L) {
            try {
                t.open()
                t.write("GET /reset HTTP/1.0\r\nHost: $host\r\n\r\n".encodeToByteArray())
                val buf = StringBuilder()
                while (true) {
                    val one = t.readExact(1) ?: break
                    buf.append(one[0].toInt().toChar())
                    if (buf.length > 128) break
                }
                buf.toString().contains("resetting")
            } catch (_: Exception) {
                false
            } finally {
                try { t.close() } catch (_: Exception) {}
            }
        } ?: false
    }
}
