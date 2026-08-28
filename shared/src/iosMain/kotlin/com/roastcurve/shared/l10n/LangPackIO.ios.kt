package com.roastcurve.shared.l10n

/** iOS 桩：语言包导入暂未接入（iOS 端开发中），返回不可用 */
private object UnsupportedIO : LangPackIO {
    override suspend fun importFrom(bytes: ByteArray): Result<String> =
        Result.failure(IllegalStateException("当前平台不支持语言包导入"))
    override suspend fun exportCurrent(): ByteArray? = null
    override suspend fun listSaved(): List<String> = emptyList()
    override suspend fun loadSaved(fileName: String): Result<String> =
        Result.failure(IllegalStateException("当前平台不支持"))
}

actual fun langPackIO(): LangPackIO = UnsupportedIO
