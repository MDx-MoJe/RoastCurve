package com.roastcurve.shared.bridge

/**
 * 豆袋 (CoffeeBeanTracker) 互联桥——平台实现由 androidMain 提供。
 * iOS 端返回空列表 / 失败桩，待 iOS 版互联可用后升级。
 */
interface BeanBagBridge {
    /**
     * 列出豆袋里的生豆批次（id / 名称 / 剩余克重）。
     * 返回 Result：失败时携带可读错误原因（未安装/版本过旧/权限被拒/进程无法拉起等），
     * UI 据此区分展示，而非一律显示"读不到"。
     */
    suspend fun listGreenBeans(): Result<List<GreenBeanSummary>>

    /**
     * 推送一炉烘焙消耗。
     * @param roastId 本炉唯一 ID（幂等键，重复推送不重复扣）
     * @param greenBeanId 豆袋生豆批次 id
     * @param grams 本次消耗克重（= 入豆重）
     */
    suspend fun consume(roastId: String, greenBeanId: Long, grams: Double): BridgeResult

    /**
     * 只补熟豆入库，不碰生豆库存（手动补录场景：生豆已扣过）。
     * 豆袋端用独立幂等键，重复推送不重复入库。
     */
    suspend fun addRoasted(
        roastId: String,
        beanName: String,
        roastedGrams: Double,
        roastLevel: String,
        roastDateEpochMs: Long,
    ): BridgeResult
}

/** 平台工厂：android 返回真实桥，其他平台返回不可用桩 */
expect fun beanBagBridge(): BeanBagBridge

/** 桥接是否在本平台可用（用于 UI 决定是否显示入口） */
expect fun isBridgeAvailableOnPlatform(): Boolean
