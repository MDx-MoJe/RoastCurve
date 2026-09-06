package com.roastcurve.shared.storage

import com.roastcurve.shared.AppDirs
import java.io.File

actual fun appStorageDir(): String = AppDirs.filesDir

internal actual fun ensureDir(path: String) {
    File(path).mkdirs()
}

internal actual fun writeFile(path: String, content: String) {
    // 原子写：先写同目录临时文件再 rename（rename 同目录内原子），
    // 防写一半被杀/断电留下截断 JSON（设置/会话/记录损坏 = 整炉或全部配置丢失）
    val tmp = File(path + ".tmp")
    tmp.writeText(content, Charsets.UTF_8)
    if (!tmp.renameTo(File(path))) {
        // rename 失败（罕见）：退化为直接写，避免写入完全丢失
        File(path).writeText(content, Charsets.UTF_8)
    }
}

internal actual fun readFile(path: String): String =
    File(path).readText(Charsets.UTF_8)

internal actual fun deleteFile(path: String) {
    File(path).delete()
}

internal actual fun listFiles(dir: String): List<String> =
    File(dir).listFiles()?.map { it.name } ?: emptyList()