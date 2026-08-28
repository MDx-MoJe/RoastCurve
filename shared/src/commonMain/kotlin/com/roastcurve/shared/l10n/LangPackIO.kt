package com.roastcurve.shared.l10n

/**
 * 语言包导入导出（commonMain 契约）
 * - zip 容器：lang.zip 内含 lang.json
 * - 平台实现负责解 zip、读文件；解析与回退逻辑在 L10n
 */
interface LangPackIO {
    /** 从用户选择的 zip/json 文件字节导入并应用 */
    suspend fun importFrom(bytes: ByteArray): Result<String>

    /** 导出当前语言包为 zip 字节（给社区改翻译的底稿） */
    suspend fun exportCurrent(): ByteArray?

    /** 已保存的语言包列表（文件名） */
    suspend fun listSaved(): List<String>

    /** 加载已保存的语言包 */
    suspend fun loadSaved(fileName: String): Result<String>
}

expect fun langPackIO(): LangPackIO
