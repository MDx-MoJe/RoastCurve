package com.roastcurve.shared.bridge

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 豆袋互联桥 Android 实现：跨应用调用豆袋的 BeanBridgeProvider（内容提供器）。
 *
 * 契约：
 *   content://com.coffee.beantracker.bridge/green_beans → 查询生豆批次
 *   call("consume", extras{roast_id, bean_id, grams})   → 幂等扣减
 */
class AndroidBeanBagBridge(private val context: Context) : BeanBagBridge {

    companion object {
        private val CONTENT_URI: Uri = Uri.parse("content://com.coffee.beantracker.bridge/green_beans")
        private const val METHOD_CONSUME = "consume"
        private const val EXTRA_ROAST_ID = "roast_id"
        private const val EXTRA_GREEN_BEAN_ID = "bean_id"
        private const val EXTRA_GRAMS = "grams"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_MESSAGE = "message"
    }

    override suspend fun listGreenBeans(): List<GreenBeanSummary> = withContext(Dispatchers.IO) {
        try {
            val cursor: Cursor? = context.contentResolver.query(CONTENT_URI, null, null, null, null)
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow("_id")
                val nameCol = c.getColumnIndexOrThrow("name")
                val gramsCol = c.getColumnIndexOrThrow("remainingGrams")
                buildList {
                    while (c.moveToNext()) {
                        add(
                            GreenBeanSummary(
                                id = c.getLong(idCol),
                                name = c.getString(nameCol),
                                remainingGrams = c.getDouble(gramsCol),
                            )
                        )
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()   // 豆袋未安装 / 无权限 → 列表为空，UI 显示不可用态
        }
    }

    override suspend fun consume(roastId: String, greenBeanId: Long, grams: Double): BridgeResult =
        withContext(Dispatchers.IO) {
            try {
                val extras = Bundle().apply {
                    putString(EXTRA_ROAST_ID, roastId)
                    putLong(EXTRA_GREEN_BEAN_ID, greenBeanId)
                    putDouble(EXTRA_GRAMS, grams)
                }
                val out = context.contentResolver.call(CONTENT_URI, METHOD_CONSUME, null, extras)
                    ?: return@withContext BridgeResult.Err("豆袋无响应（可能未安装或版本过旧）")
                val ok = out.getString(EXTRA_RESULT) == "ok"
                val msg = out.getString(EXTRA_MESSAGE).orEmpty()
                if (ok) BridgeResult.Ok(msg) else BridgeResult.Err(msg.ifBlank { "未知错误" })
            } catch (e: Exception) {
                BridgeResult.Err(e.message ?: e.javaClass.simpleName)
            }
        }
}

actual fun beanBagBridge(): BeanBagBridge = AndroidBeanBagBridge(
    requireNotNull(appContextBridge) { "豆袋互联桥上下文未初始化（App 尚未启动）" }
)

/** Application.onCreate 时由 App 注入；未注入前调用会抛异常，桥入口均做了判空保护 */
var appContextBridge: Context? = null

actual fun isBridgeAvailableOnPlatform(): Boolean = appContextBridge != null
