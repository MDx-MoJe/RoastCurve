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

package com.roastcurve.shared.diag

import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.protocol.BridgeDiag
import com.roastcurve.shared.protocol.BridgeStatus
import com.roastcurve.shared.protocol.ModbusTcp
import com.roastcurve.shared.protocol.SignalProbe
import com.roastcurve.shared.protocol.TcpByteTransport
import com.roastcurve.shared.protocol.fetchBridgeDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

/**
 * 连接诊断引擎
 *
 * 链路：手机 → WiFi → 桥接器(8899 TCP) → RS485 → 温控器
 *
 * 诊断的核心不是「报错」，而是把「连接失败」这句废话拆成可执行的一条判断。
 * 关键手段是拿桥接器自检报告（/diag）当对照：固件自己每秒都在读温控器，
 * 它读得到而你读不到，问题必然在 8899 通道；它自己也读不到，问题就在总线上。
 */

enum class DiagStatus { PASS, FAIL, SKIP }

data class DiagStep(
    val id: String,
    val title: String,
    val detail: String,
    val status: DiagStatus,
    val raw: String = "",
    val elapsedMs: Long = 0,
)

data class DiagReport(
    val host: String,
    val steps: List<DiagStep>,
    val verdict: String,       // 一句话结论
    val advice: String,        // 下一步怎么办
    val rawSnapshot: String,   // 导出用完整快照
    val allPass: Boolean,
    val at: Long = Clock.System.now().toEpochMilliseconds(),
)

/** 诊断过程中收集到的现场材料（导出诊断包用） */
private class Collector {
    val lines = StringBuilder()
    fun add(layer: String, text: String) {
        lines.append("[").append(layer).append("] ").append(text).append('\n')
    }
}

private fun isLikelyHost(h: String): Boolean {
    val t = h.trim()
    if (t.isEmpty()) return false
    // IPv4 或主机名（roastbridge / roastbridge.local）
    if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(t)) {
        return t.split(".").all { it.toIntOrNull() in 0..255 }
    }
    return t.matches(Regex("^[A-Za-z0-9][A-Za-z0-9.-]*$"))
}

/**
 * 跑完整诊断。每完成一层回调一次，UI 可以边跑边显示。
 */
suspend fun runConnectDiagnostics(
    hostRaw: String,
    slaveId: Int,
    pvReg: Int,
    svReg: Int,
    baud: Long? = null,
    onStep: (DiagStep) -> Unit = {},
): DiagReport {
    val host = hostRaw.trim()
    val col = Collector()
    val steps = mutableListOf<DiagStep>()

    fun emit(s: DiagStep): DiagStep {
        steps += s
        col.add(s.id, "${s.status} | ${s.detail}${if (s.raw.isNotBlank()) " | raw=" + s.raw.replace('\n', ' ') else ""}")
        onStep(s)
        return s
    }

    // ========== S1 配置检查 ==========
    val s1Start = nowMs()
    val s1Ok = isLikelyHost(host)
    emit(
        DiagStep(
            id = "CONFIG",
            title = L10n.get("diag.s1"),
            detail = when {
                host.isEmpty() -> L10n.get("diag.d1_empty")
                !s1Ok -> L10n.get("diag.d1_bad", "host" to host)
                else -> L10n.get("diag.d1_ok", "host" to host)
            },
            status = if (s1Ok) DiagStatus.PASS else DiagStatus.FAIL,
            raw = "host=$host slave=$slaveId pvReg=0x${hex4(pvReg)} svReg=0x${hex4(svReg)} baud=${baud ?: "-"}",
            elapsedMs = nowMs() - s1Start,
        )
    )
    if (!s1Ok) {
        return finish(host, steps, "diag.v1", "diag.a1", col)
    }

    // ========== S2 桥接器 HTTP 状态口（8898）==========
    val s2Start = nowMs()
    val status: BridgeStatus? = SignalProbe.fetchStatus(host)
    emit(
        DiagStep(
            id = "HTTP_8898",
            title = L10n.get("diag.s2"),
            detail = if (status == null) {
                L10n.get("diag.d2_fail")
            } else {
                L10n.get(
                    "diag.d2_ok",
                    "ver" to (status.ver ?: "?"),
                    "rssi" to (status.rssi?.toString() ?: "?"),
                    "uptime" to (status.uptimeSec?.toString() ?: "?"),
                )
            },
            status = if (status == null) DiagStatus.FAIL else DiagStatus.PASS,
            raw = status?.let {
                "ver=${it.ver} rssi=${it.rssi} uptime=${it.uptimeSec}s client=${it.client} " +
                    "holder=${it.holder} link=${it.link} pv=${it.pv} sv=${it.sv} " +
                    "appTx=${it.appTx} appRx=${it.appRx} pollOkAge=${it.pollOkAgeMs}"
            } ?: L10n.get("diag.raw_none"),
            elapsedMs = nowMs() - s2Start,
        )
    )
    if (status == null) {
        // 附带一次 mDNS 发现，帮用户找回漂移的 IP
        val found = runCatching {
            com.roastcurve.shared.protocol.discoverBridge(2500L)
        }.getOrNull()
        if (found != null && found.host != host) {
            emit(
                DiagStep(
                    id = "MDNS_HINT",
                    title = L10n.get("diag.s2b"),
                    detail = L10n.get("diag.d2b_found", "newHost" to found.host, "oldHost" to host),
                    status = DiagStatus.FAIL,
                    raw = "mDNS: ${found.serviceName} → ${found.host}",
                )
            )
        } else if (found != null) {
            emit(
                DiagStep(
                    id = "MDNS_HINT",
                    title = L10n.get("diag.s2b"),
                    detail = L10n.get("diag.d2b_same", "host" to found.host),
                    status = DiagStatus.PASS,
                    raw = "mDNS: ${found.serviceName} → ${found.host}",
                )
            )
        }
        return finish(host, steps, "diag.v2", "diag.a2", col)
    }

    // ========== S3 固件自检（/diag，v1.9.5+）==========
    val s3Start = nowMs()
    val bd: BridgeDiag? = fetchBridgeDiag(host)
    var busOk: Boolean? = null       // 总线到温控器是否通（固件视角）
    if (bd == null) {
        emit(
            DiagStep(
                id = "FW_DIAG",
                title = L10n.get("diag.s3"),
                detail = L10n.get("diag.d3_old", "ver" to (status.ver ?: "?")),
                status = DiagStatus.SKIP,
                raw = L10n.get("diag.raw_none"),
                elapsedMs = nowMs() - s3Start,
            )
        )
    } else {
        col.add("FW_DIAG_RAW", bd.raw)
        val cfgMismatch = buildList {
            if (bd.baud != null && baud != null && bd.baud != baud) add("baud ${bd.baud}≠$baud")
            if (bd.slave != null && bd.slave != slaveId) add("slave ${bd.slave}≠$slaveId")
            if (bd.pvReg != null && bd.pvReg != pvReg) add("pvReg ${bd.pvReg}≠$pvReg")
            if (bd.svReg != null && bd.svReg != svReg) add("svReg ${bd.svReg}≠$svReg")
        }
        val detail = when {
            cfgMismatch.isNotEmpty() -> L10n.get("diag.d3_mismatch", "diff" to cfgMismatch.joinToString(", "))
            bd.sim -> L10n.get("diag.d3_sim")
            bd.probeOk == 1 -> L10n.get(
                "diag.d3_ok",
                "pv" to (bd.probePv ?: bd.pv ?: 0),
                "rtt" to (bd.probeRttMs ?: 0),
            )
            else -> L10n.get("diag.d3_fail", "reason" to failText(bd.probeFail))
        }
        busOk = when {
            bd.sim -> null
            bd.probeOk == 1 -> true
            bd.probeOk == 0 -> false
            else -> null
        }
        emit(
            DiagStep(
                id = "FW_DIAG",
                title = L10n.get("diag.s3"),
                detail = detail,
                status = when {
                    cfgMismatch.isNotEmpty() -> DiagStatus.FAIL
                    bd.sim -> DiagStatus.SKIP
                    bd.probeOk == 1 -> DiagStatus.PASS
                    else -> DiagStatus.FAIL
                },
                raw = buildString {
                    append("probe.ok=${bd.probeOk} fail=${bd.probeFail} pv=${bd.probePv} rtt=${bd.probeRttMs}ms\n")
                    if (!bd.probeTx.isNullOrBlank()) append("tx=${bd.probeTx}\n")
                    if (!bd.probeRx.isNullOrBlank()) append("rx=${bd.probeRx}\n")
                    append("uart baud=${bd.baud} slave=${bd.slave} pvReg=${bd.pvReg} svReg=${bd.svReg} ")
                    append("pinTx=${bd.pinTx} pinRx=${bd.pinRx}\n")
                    append("poll tx=${bd.pollTx} rx=${bd.pollRx} to=${bd.pollTimeout} bad=${bd.pollBad} ")
                    append("age=${bd.pollAgeMs}ms link=${bd.link} pv=${bd.pv} sv=${bd.sv}\n")
                    append("app  tx=${bd.appTx} rx=${bd.appRx} to=${bd.appTimeout} bad=${bd.appBad} ")
                    append("rtt=${bd.appRttMs}ms lastReqAge=${bd.lastReqAgeMs}ms\n")
                    append("tcp  client=${bd.client} holder=${bd.holder}")
                },
                elapsedMs = nowMs() - s3Start,
            )
        )
    }

    // ========== S4 8899 端口连通性 ==========
    val s4Start = nowMs()
    var tcpOpen = false
    var tcpErr = ""
    withTimeoutOrNull(3000L) {
        val t = TcpByteTransport(host = host, port = 8899, readTimeoutMs = 1000L)
        try {
            t.open()
            tcpOpen = true
        } catch (e: Exception) {
            tcpErr = (e.message ?: e::class.simpleName ?: "?")
        } finally {
            try { t.close() } catch (_: Exception) {}
        }
    }
    emit(
        DiagStep(
            id = "TCP_8899",
            title = L10n.get("diag.s4"),
            detail = if (tcpOpen) L10n.get("diag.d4_ok") else L10n.get("diag.d4_fail"),
            status = if (tcpOpen) DiagStatus.PASS else DiagStatus.FAIL,
            raw = if (tcpOpen) "tcp connect ok" else "tcp connect failed: $tcpErr",
            elapsedMs = nowMs() - s4Start,
        )
    )
    if (!tcpOpen) {
        val v = if (busOk == true) "diag.v5" else "diag.v4"
        val a = if (busOk == true) "diag.a5" else "diag.a4"
        return finish(host, steps, v, a, col)
    }

    // ========== S5 App 侧 Modbus 事务（端到端）==========
    val s5Start = nowMs()
    var pvValue: Int? = null
    var txErr = ""
    withTimeoutOrNull(6000L) {
        val t = TcpByteTransport(host = host, port = 8899, readTimeoutMs = 2000L)
        try {
            t.open()
            t.drain()
            val req = ModbusTcp.buildReadRequest(1, slaveId, ModbusTcp.FUNCTION_READ_HOLDING, pvReg, 1)
            t.write(req)
            val rsp = t.readExact(11)
            if (rsp == null) {
                txErr = "no response"
            } else {
                val vals = ModbusTcp.parseReadResponse(req, rsp)
                pvValue = vals.firstOrNull()
            }
        } catch (e: Exception) {
            txErr = e.message ?: (e::class.simpleName ?: "?")
        } finally {
            try { t.close() } catch (_: Exception) {}
        }
    }
    val txOk = pvValue != null
    emit(
        DiagStep(
            id = "MODBUS_TX",
            title = L10n.get("diag.s5"),
            detail = if (txOk) {
                L10n.get("diag.d5_ok", "pv" to (pvValue ?: 0))
            } else {
                L10n.get("diag.d5_fail", "err" to txErr)
            },
            status = if (txOk) DiagStatus.PASS else DiagStatus.FAIL,
            raw = if (txOk) "pv=$pvValue" else "tx failed: $txErr",
            elapsedMs = nowMs() - s5Start,
        )
    )

    // ========== S6 数值合理性 ==========
    if (txOk) {
        val pv = pvValue!!
        val sane = pv in 0..500
        emit(
            DiagStep(
                id = "VALUE",
                title = L10n.get("diag.s6"),
                detail = if (sane) L10n.get("diag.d6_ok", "pv" to pv) else L10n.get("diag.d6_bad", "pv" to pv),
                status = if (sane) DiagStatus.PASS else DiagStatus.FAIL,
                raw = "pv=$pv",
            )
        )
        if (!sane) return finish(host, steps, "diag.v6", "diag.a6", col)
    }

    // ========== 结论 ==========
    return when {
        txOk -> finish(host, steps, "diag.v0", "diag.a0", col)
        // 固件自己读得到温控器，你读不到 → 问题在 8899 通道/会话
        busOk == true -> finish(host, steps, "diag.v5", "diag.a5", col)
        // 固件都读不到 → 总线/温控器侧
        busOk == false -> finish(host, steps, "diag.v3", "diag.a3", col)
        // 老固件 + App 读不到：无法区分，给升级建议
        else -> finish(host, steps, "diag.v7", "diag.a7", col)
    }
}

private fun finish(
    host: String,
    steps: List<DiagStep>,
    verdictKey: String,
    adviceKey: String,
    col: Collector,
): DiagReport {
    val allPass = steps.isNotEmpty() && steps.all { it.status == DiagStatus.PASS }
    return DiagReport(
        host = host,
        steps = steps,
        verdict = L10n.get(verdictKey),
        advice = L10n.get(adviceKey),
        rawSnapshot = col.lines.toString(),
        allPass = allPass,
    )
}

private fun failText(fail: String?): String = when (fail) {
    "timeout" -> L10n.get("diag.f_timeout")
    "crc" -> L10n.get("diag.f_crc")
    "short" -> L10n.get("diag.f_short")
    "slaveid" -> L10n.get("diag.f_slaveid")
    "exception" -> L10n.get("diag.f_exception")
    "sim" -> L10n.get("diag.f_sim")
    else -> fail ?: L10n.get("diag.f_unknown")
}

private fun hex4(v: Int): String {
    val s = v.toString(16).uppercase()
    return s.padStart(4, '0')
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
