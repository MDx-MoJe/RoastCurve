package com.roastcurve.shared.protocol

/**
 * 桥接器自动发现（mDNS / Bonjour）。
 * 固件连上 WiFi 后注册了 `roastbridge.local`（服务类型 `_roastbridge._tcp`），
 * App 用 mDNS 解析出真实 IP，配网完成后自动填写、免手动找 IP。
 */

/** mDNS 发现结果：桥接器的局域网 IP */
data class BridgeInfo(val host: String, val serviceName: String)

/**
 * 发现桥接器 IP。
 * @param timeoutMs 最多等待时长（桥接器配网后需几秒重启连 WiFi）
 * @return 找到的 IP，找不到返回 null
 */
expect suspend fun discoverBridge(timeoutMs: Long): BridgeInfo?
