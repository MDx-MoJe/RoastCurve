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

import com.roastcurve.shared.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桥接器侧自检报告（固件 v1.9.5+ 的 GET /diag）
 *
 * 设计意图：App 侧只能看到「8899 有没有回话」，看不到总线上的真相。
 * 固件的 /diag 会现场做一次真实 RS485 事务，把两侧计数一起吐出来：
 *   - poll*  固件自己的轮询器（每秒读 PV/SV，与 App 无关）
 *   - app*   App 透传路径（App 实际走的那条）
 *   - probe  固件现场单次探针（最权威的总线判据）
 *
 * 老固件（< 1.9.5）没有 /diag，返回 null，调用方降级为纯 App 侧判断。
 */
data class BridgeDiag(
    val ver: String? = null,
    val uptimeSec: Long? = null,
    val ip: String? = null,
    val rssi: Int? = null,
    val sim: Boolean = false,
    // TCP 会话
    val client: Int? = null,
    val holder: String? = null,
    val lastReqAgeMs: Long? = null,
    // UART / Modbus 配置（固件当前生效值，可与 App 设置比对）
    val baud: Long? = null,
    val slave: Int? = null,
    val pvReg: Int? = null,
    val svReg: Int? = null,
    val pinTx: Int? = null,
    val pinRx: Int? = null,
    // App 透传路径计数
    val appTx: Long? = null,
    val appRx: Long? = null,
    val appTimeout: Long? = null,
    val appBad: Long? = null,
    val appRttMs: Long? = null,
    // 固件轮询路径计数
    val pollTx: Long? = null,
    val pollRx: Long? = null,
    val pollTimeout: Long? = null,
    val pollBad: Long? = null,
    val pollAgeMs: Long? = null,
    // 温控器缓存值
    val link: Boolean? = null,
    val pv: Int? = null,
    val sv: Int? = null,
    // 现场探针结果
    val probeOk: Int = -1,          // -1 未执行（SIM）/ 0 失败 / 1 成功
    val probePv: Int? = null,
    val probeRttMs: Long? = null,
    val probeFail: String? = null,  // timeout / crc / short / slaveid / exception / sim
    val probeTx: String? = null,    // 十六进制原始帧
    val probeRx: String? = null,
    val raw: String = "",
) {
    /** 固件是否支持自检口（老固件拿不到，只能降级） */
    val supported: Boolean get() = ver != null || raw.isNotBlank()
}

/**
 * 读取桥接器自检报告（8898 /diag）。不可达/不支持返回 null。
 *
 * 【2026-10-21 实测坑】固件在 /diag 里会先同步跑一次 RS485 探针（正常 260ms，
 * 总线不通则打满 800ms），加上主循环排空其他任务，首字节可能 1 秒后才到。
 * 早期实现用 1.2s 读超时逐字节读，结果在首字节前就超时，把“没读到”
 * 误判成“固件不支持自检”。现在：头 5s 读超时、整体 15s，且先解析头里的
 * Content-Length 再整块读体。
 */
suspend fun fetchBridgeDiag(host: String, timeoutMs: Long = 15_000L): BridgeDiag? =
    withContext(Dispatchers.IO) {
        val t = TcpByteTransport(host = host, port = 8898, readTimeoutMs = 5_000L)
        withTimeoutOrNull(timeoutMs) {
            try {
                t.open()
                t.write("GET /diag HTTP/1.0\r\nHost: $host\r\n\r\n".encodeToByteArray())
                // 逐字节读 HTTP 头（不能预设长度，固件要先跑完探针才会回）
                val head = StringBuilder()
                while (head.length < 1024) {
                    val one = t.readExact(1) ?: break
                    head.append(one[0].toInt().toChar())
                    if (head.endsWith("\r\n\r\n")) break
                }
                if (!head.endsWith("\r\n\r\n")) {
                    DiagLog.warn("DIAG", "/diag 无响应头（已收 ${head.length} 字节），桥接器可能正忙")
                    return@withTimeoutOrNull null
                }
                val len = Regex("(?i)content-length:\\s*(\\d+)")
                    .find(head.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 900
                val body = t.readExact(len.coerceIn(1, 4096))
                if (body == null) {
                    DiagLog.warn("DIAG", "/diag 响应体不完整（预期 $len 字节）")
                    return@withTimeoutOrNull null
                }
                parseDiag(body.decodeToString())
            } catch (e: Exception) {
                DiagLog.warn("DIAG", "/diag 请求异常：${e.message}")
                null
            } finally {
                try { t.close() } catch (_: Exception) {}
            }
        }
    }

/** 极简 JSON 取字段（不引依赖，固件输出格式固定） */
private fun jsonNum(body: String, key: String): Long? {
    val i = body.indexOf("\"$key\":")
    if (i < 0) return null
    val rest = body.substring(i + key.length + 3).trimStart()
    if (rest.isEmpty()) return null
    val m = Regex("-?\\d+").find(rest) ?: return null
    return m.value.toLongOrNull()
}

private fun jsonStr(body: String, key: String): String? {
    val i = body.indexOf("\"$key\":\"")
    if (i < 0) return null
    val rest = body.substring(i + key.length + 4)
    return rest.takeWhile { it != '"' }
}

/** 提取嵌套对象原文（大括号配平），用于分区后再取同名字段 */
private fun jsonObj(body: String, key: String): String? {
    val i = body.indexOf("\"$key\":{")
    if (i < 0) return null
    val start = i + key.length + 3
    if (start >= body.length) return null
    var depth = 0
    for (j in start until body.length) {
        when (body[j]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return body.substring(start, j + 1)
            }
        }
    }
    return null
}

private fun parseDiag(http: String): BridgeDiag? {
    // 剥掉 HTTP 头
    val bodyIdx = http.indexOf("\r\n\r\n")
    val body = if (bodyIdx >= 0) http.substring(bodyIdx + 4) else http
    if (body.indexOf("\"ver\":") < 0) return null   // 不是 /diag 的响应（老固件会回 /status 之外的东西）

    val tcp = jsonObj(body, "tcp") ?: ""
    val uart = jsonObj(body, "uart") ?: ""
    val probe = jsonObj(body, "probe") ?: ""

    return BridgeDiag(
        ver = jsonStr(body, "ver"),
        uptimeSec = jsonNum(body, "uptime"),
        ip = jsonStr(body, "ip"),
        rssi = jsonNum(body, "rssi")?.toInt(),
        sim = jsonNum(body, "sim") == 1L,
        client = tcp.let { jsonNum(it, "client")?.toInt() },
        holder = tcp.let { jsonStr(it, "holder") },
        lastReqAgeMs = tcp.let { jsonNum(it, "lastReqAgeMs") },
        baud = uart.let { jsonNum(it, "baud") },
        slave = uart.let { jsonNum(it, "slave")?.toInt() },
        pvReg = uart.let { jsonNum(it, "pvReg")?.toInt() },
        svReg = uart.let { jsonNum(it, "svReg")?.toInt() },
        pinTx = uart.let { jsonNum(it, "pinTx")?.toInt() },
        pinRx = uart.let { jsonNum(it, "pinRx")?.toInt() },
        appTx = uart.let { jsonNum(it, "appTx") },
        appRx = uart.let { jsonNum(it, "appRx") },
        appTimeout = uart.let { jsonNum(it, "appTimeout") },
        appBad = uart.let { jsonNum(it, "appBad") },
        appRttMs = uart.let { jsonNum(it, "appRttMs") },
        pollTx = uart.let { jsonNum(it, "pollTx") },
        pollRx = uart.let { jsonNum(it, "pollRx") },
        pollTimeout = uart.let { jsonNum(it, "pollTimeout") },
        pollBad = uart.let { jsonNum(it, "pollBad") },
        pollAgeMs = uart.let { jsonNum(it, "pollAgeMs") },
        link = uart.let { jsonNum(it, "link")?.let { v -> v == 1L } },
        pv = uart.let { jsonNum(it, "pv")?.toInt() },
        sv = uart.let { jsonNum(it, "sv")?.toInt() },
        probeOk = probe.let { jsonNum(it, "ok")?.toInt() } ?: -1,
        probePv = probe.let { jsonNum(it, "pv")?.toInt() },
        probeRttMs = probe.let { jsonNum(it, "rttMs") },
        probeFail = probe.let { jsonStr(it, "fail") },
        probeTx = probe.let { jsonStr(it, "tx") },
        probeRx = probe.let { jsonStr(it, "rx") },
        raw = body.trim(),
    )
}
