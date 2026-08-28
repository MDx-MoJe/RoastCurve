package com.roastcurve.shared.l10n

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 语言包 IO：
 * - 已保存语言包目录：filesDir/langpacks/
 * - zip 内取第一个 lang.json；裸 json 亦兼容
 */
class AndroidLangPackIO(private val context: Context) : LangPackIO {

    private val dir: File get() = File(context.filesDir, "langpacks").apply { mkdirs() }

    private fun jsonFromBytes(bytes: ByteArray): String? = try {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            var text: String? = null
            ZipInputStream(bytes.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null && text == null) {
                    if (!e.isDirectory && e.name.endsWith(".json")) text = zis.readBytes().decodeToString()
                    e = zis.nextEntry
                }
            }
            text
        } else bytes.decodeToString().takeIf { it.trimStart().startsWith("{") }
    } catch (_: Exception) { null }

    private fun zipOf(json: String, fileName: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("lang.json"))
            zos.write(json.toByteArray())
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    override suspend fun importFrom(bytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val text = jsonFromBytes(bytes)
                ?: return@withContext Result.failure(IllegalStateException("文件格式无法识别（需要语言包 zip 或 json）"))
            val pack = L10n.parsePack(text).getOrElse { return@withContext Result.failure(it) }
            val safeName = pack.meta.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            File(dir, "$safeName.json").writeText(text)
            L10n.applyPack(pack)
            Result.success(pack.meta.name)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportCurrent(): ByteArray? = withContext(Dispatchers.IO) {
        val s = L10n.state.value
        val strings: Map<String, String> = when {
            s.pack != null -> s.pack.strings
            else -> BuiltinEn.strings
        }
        val pack = L10n.LangPack(
            meta = L10n.LangMeta(name = s.displayName, version = 1, authors = listOf("MDx")),
            strings = strings,
        )
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
            .encodeToString(L10n.LangPack.serializer(), pack)
        try { zipOf(json, "lang.json") } catch (_: Exception) { null }
    }

    override suspend fun listSaved(): List<String> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    }

    override suspend fun loadSaved(fileName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val f = File(dir, "$fileName.json")
            val text = f.readText()
            val pack = L10n.parsePack(text).getOrElse { return@withContext Result.failure(it) }
            L10n.applyPack(pack)
            Result.success(pack.meta.name)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

actual fun langPackIO(): LangPackIO {
    val ctx = (com.roastcurve.shared.AppDirs.androidContext as? android.content.Context)
        ?: (com.roastcurve.shared.bridge.appContextBridge as? android.content.Context)
    return AndroidLangPackIO(requireNotNull(ctx) { "语言包 IO 上下文未注入" })
}
