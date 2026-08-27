package com.roastcurve.app.history

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

@OptIn(ExperimentalForeignApi::class)
actual fun shareText(filename: String, content: String) {
    val items = listOf("$filename\n\n$content")
    val controller = UIActivityViewController(items, null)
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController
    root?.presentViewController(controller, true, null)
}
actual fun exportBackupToDownloads(filename: String, content: String): String? = null

actual fun exportBackupToDownloads(filename: String, data: ByteArray): String? = null

actual fun packBackupZip(filename: String, json: String): ByteArray? = null

actual fun unpackBackupZip(data: ByteArray): String? =
    data.decodeToString().takeIf { it.startsWith("{") }
