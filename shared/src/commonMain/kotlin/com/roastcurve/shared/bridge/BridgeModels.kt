package com.roastcurve.shared.bridge

/**
 * 豆袋互联数据（commonMain 定义，平台无关）
 */
data class GreenBeanSummary(
    val id: Long,
    val name: String,
    val remainingGrams: Double,
)

/** 推送结果 */
sealed interface BridgeResult {
    data class Ok(val message: String) : BridgeResult
    data class Err(val message: String) : BridgeResult
}
