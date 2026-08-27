package com.roastcurve.shared.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桥接器信号强度探测：读取固件开的 HTTP 状态口 /status（端口 8898），
 * 解析出 RSSI（WiFi 信号强度，dBm）。用裸 TCP 手写 GET，避免引入额外 HTTP 客户端依赖。
 */
object SignalProbe {

    /** 单次探测，返回 RSSI（dBm）或 null（不可达/超时/解析失败）。超时很短，不阻塞连接主流程 */
    suspend fun fetchRssi(host: String): Int? = withContext(Dispatchers.IO) {
        val t = TcpByteTransport(host = host, port = 8898, readTimeoutMs = 1500L)
        withTimeoutOrNull(2500L) {
            try {
                t.open()
                t.write("GET /status HTTP/1.0\r\nHost: $host\r\n\r\n".encodeToByteArray())
                // 状态口响应很小（<200 字节），逐字节读到连接关闭（readExact 返回 null）
                val buf = StringBuilder()
                while (true) {
                    val one = t.readExact(1) ?: break
                    buf.append(one[0].toInt().toChar())
                    if (buf.length > 512) break
                }
                val body = buf.toString()
                val idx = body.indexOf("\"rssi\":")
                if (idx >= 0) {
                    val rest = body.substring(idx + 7)
                    val num = rest.takeWhile { it.isDigit() || it == '-' }
                    if (num.isNotEmpty()) num.toIntOrNull() else null
                } else null
            } catch (_: Exception) {
                null
            } finally {
                try { t.close() } catch (_: Exception) {}
            }
        }
    }
}
