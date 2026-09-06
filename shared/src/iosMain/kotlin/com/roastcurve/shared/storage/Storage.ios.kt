package com.roastcurve.shared.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
internal actual fun appStorageDir(): String {
    val docs = NSFileManager.defaultManager.URLForDirectory(
        NSDocumentDirectory, NSUserDomainMask, null, true, null
    ) as NSURL?
    return (docs?.path ?: NSHomeDirectory()) + "/roasts_data"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun ensureDir(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path, true, null, null
    )
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeFile(path: String, content: String) {
    val data = content.encodeToByteArray().toNSData()
    // atomically=true：先写临时文件再替换，防写一半崩溃留下截断 JSON
    if (!data.writeToFile(path, true)) {
        // 失败退化：createFileAtPath 兜底（原行为）
        NSFileManager.defaultManager.createFileAtPath(path, data, null)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun readFile(path: String): String {
    val data = NSFileManager.defaultManager.contentsAtPath(path)
        ?: throw IllegalStateException("cannot read $path")
    return data.toByteArray().decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun listFiles(dir: String): List<String> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)
        ?.filterIsInstance<String>() ?: emptyList()