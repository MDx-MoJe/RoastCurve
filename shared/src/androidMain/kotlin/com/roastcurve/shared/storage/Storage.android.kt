package com.roastcurve.shared.storage

import com.roastcurve.shared.AppDirs
import java.io.File

actual fun appStorageDir(): String = AppDirs.filesDir

internal actual fun ensureDir(path: String) {
    File(path).mkdirs()
}

internal actual fun writeFile(path: String, content: String) {
    File(path).writeText(content, Charsets.UTF_8)
}

internal actual fun readFile(path: String): String =
    File(path).readText(Charsets.UTF_8)

internal actual fun deleteFile(path: String) {
    File(path).delete()
}

internal actual fun listFiles(dir: String): List<String> =
    File(dir).listFiles()?.map { it.name } ?: emptyList()