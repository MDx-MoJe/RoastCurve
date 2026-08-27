package com.roastcurve.shared.bridge

/**
 * 非 Android 平台桩：iOS 端暂无豆袋互联能力
 */
private object UnsupportedBridge : BeanBagBridge {
    override suspend fun listGreenBeans(): List<GreenBeanSummary> = emptyList()
    override suspend fun consume(roastId: String, greenBeanId: Long, grams: Double): BridgeResult =
        BridgeResult.Err("当前平台不支持豆袋互联")
}

actual fun beanBagBridge(): BeanBagBridge = UnsupportedBridge
