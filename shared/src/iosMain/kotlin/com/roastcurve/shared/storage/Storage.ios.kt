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
    NSFileManager.defaultManager.createFileAtPath(
        path, content.encodeToByteArray().toNSData(), null
    )
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