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
    val uptimeSec: Long? = null,
    // ==== v1.9.5 诊断字段（老固件不上报 → null，调用方降级）====
    val client: Int? = null,       // 8899 上有无客户端连接
    val holder: String? = null,    // 当前主控：app / web / none
    val link: Boolean? = null,     // 固件自己轮询温控器是否正常
    val pv: Int? = null,           // 固件轮询到的温度
    val appTx: Long? = null,       // App 透传下发帧数
    val appRx: Long? = null,       // App 透传成功应答数
    val appTimeout: Long? = null,
    val appBad: Long? = null,
    val pollOkAgeMs: Long? = null, // 距上次轮询成功的毫秒数（0xFFFFFFFF = 从未成功）
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
        fun num(name: String): Long? = field(name)?.toLongOrNull()
        return BridgeStatus(
            rssi = field("rssi")?.toIntOrNull(),
            safe = field("safe")?.let { it == "1" || it == "true" },
            sv = field("sv")?.toIntOrNull(),
            fanSpeed = field("fan_speed")?.toIntOrNull(),
            ver = field("ver"),
            uptimeSec = num("uptime"),
            client = num("client")?.toInt(),
            holder = field("holder"),
            link = field("link")?.let { it == "1" || it == "true" },
            pv = num("pv")?.toInt(),
            appTx = num("app_tx"),
            appRx = num("app_rx"),
            appTimeout = num("app_to"),
            appBad = num("app_bad"),
            pollOkAgeMs = num("poll_ok"),
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
