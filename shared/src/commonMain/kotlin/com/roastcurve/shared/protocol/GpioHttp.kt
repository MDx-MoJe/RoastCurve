package com.roastcurve.shared.protocol

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * 桥接器 GPIO 配置（HTTP /gpiocfg，固件 v1.8.1+）
 * 与 WebUI 设置页同一数据源：读当前引脚 + 可用池，写后需重启生效。
 */

/** 桥接器 GPIO 配置快照 */
data class GpioConfig(
    val pinTx: Int,
    val pinRx: Int,
    val pinFan: Int,
    val pool: List<Int> = emptyList(),
)

/** 解析固件 /gpiocfg JSON（手拆：只取 pin_tx/pin_rx/pin_fan/pool，避免引入序列化依赖） */
fun parseGpioConfig(body: String): GpioConfig? {
    fun grab(key: String): Int? {
        val i = body.indexOf("\"$key\"")
        if (i < 0) return null
        val c = body.indexOf(':', i)
        if (c < 0) return null
        var j = c + 1
        while (j < body.length && (body[j] == ' ' || body[j] == '\t')) j++
        var k = j
        while (k < body.length && (body[k].isDigit() || body[k] == '-')) k++
        return body.substring(j, k).toIntOrNull()
    }
    val tx = grab("pin_tx") ?: return null
    val rx = grab("pin_rx") ?: return null
    val fan = grab("pin_fan") ?: return null
    // pool: [2,4,5,...] 手拆
    val poolStart = body.indexOf("\"pool\"")
    val pool = mutableListOf<Int>()
    if (poolStart >= 0) {
        val br = body.indexOf('[', poolStart)
        if (br >= 0) {
            val brEnd = body.indexOf(']', br)
            if (brEnd > br) {
                val seg = body.substring(br + 1, brEnd)
                seg.split(',').forEach { s ->
                    s.trim().toIntOrNull()?.let { pool.add(it) }
                }
            }
        }
    }
    return GpioConfig(tx, rx, fan, pool)
}

/**
 * GPIO 配置 HTTP 客户端门面：把 ktor 类型藏在内层，UI 层（composeApp）无需 ktor 依赖。
 */
class GpioConfigClient(host: String) {
    private val client: HttpClient = createFanHttpClient()
    private val base = "http://$host:8898"

    /** 读桥接器固件版本（/status 的 ver 字段）；失败返回 null */
    suspend fun fetchVersion(): String? {
        return try {
            val body = client.get("$base/status").bodyAsText()
            val i = body.indexOf("\"ver\":\"")
            if (i < 0) return null
            val j = i + 8
            val k = body.indexOf('"', j)
            if (k < 0) null else body.substring(j, k)
        } catch (e: Exception) {
            null
        }
    }

    /** 读当前配置；失败返回 null */
    suspend fun fetch(): GpioConfig? = try {
        parseGpioConfig(client.get("$base/gpiocfg").bodyAsText())
    } catch (e: Exception) {
        null
    }

    /**
     * 探测桥接器：区分「旧固件（HTTP 通但无 ver 字段，≤v1.4）」与「连不上（HTTP 不通）」
     * @return (version?, httpReachable) — version=null 且 reachable=true 表示旧固件
     */
    suspend fun probe(): Pair<String?, Boolean> {
        return try {
            val body = client.get("$base/status").bodyAsText()
            val i = body.indexOf("\"ver\":\"")
            if (i < 0) Pair(null, true)
            else {
                val j = i + 8
                val k = body.indexOf('"', j)
                Pair(if (k < 0) null else body.substring(j, k), true)
            }
        } catch (e: Exception) {
            Pair(null, false)
        }
    }

    /** 写配置（tx/rx/fan 互斥由固件校验）。true=固件接受（需重启生效） */
    suspend fun save(tx: Int, rx: Int, fan: Int): Boolean = try {
        client.get("$base/gpiocfg?tx=$tx&rx=$rx&fan=$fan")
            .bodyAsText().contains("\"ok\":true")
    } catch (e: Exception) {
        false
    }
}
