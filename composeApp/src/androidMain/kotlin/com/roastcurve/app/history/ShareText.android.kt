package com.roastcurve.app.history

import com.roastcurve.shared.l10n.L10n
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

actual fun shareText(filename: String, content: String) {
    val context = com.roastcurve.shared.AppDirs.androidContext ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, filename)
        putExtra(Intent.EXTRA_TEXT, content)
    }
    if (context is Activity) {
        context.startActivity(Intent.createChooser(intent, L10n.get("app.s7", "filename" to filename)))
    }
}

actual fun exportBackupToDownloads(filename: String, data: ByteArray): String? {
    val context = com.roastcurve.shared.AppDirs.androidContext as? Context ?: return null
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(data)
        } ?: return null
        return "Download/$filename"
    }
    return try {
        val dir = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "")
            ?.apply { mkdirs() } ?: return null
        val f = java.io.File(dir, filename)
        f.writeBytes(data)
        L10n.get("app.s8", "name" to f.name)
    } catch (_: Exception) {
        null
    }
}

actual fun packBackupZip(filename: String, json: String): ByteArray? = try {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry(filename))
        zos.write(json.toByteArray())
        zos.closeEntry()
    }
    bos.toByteArray()
} catch (_: Exception) { null }

actual fun unpackBackupZip(data: ByteArray): String? = try {
    // zip 魔数 PK\x03\x04
    if (data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
        var text: String? = null
        java.util.zip.ZipInputStream(data.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && text == null) {
                if (!entry.isDirectory) {
                    text = zis.readBytes().decodeToString()
                }
                entry = zis.nextEntry
            }
        }
        text
    } else {
        data.decodeToString()   // 兼容旧的纯 JSON 备份
    }
} catch (_: Exception) { null }
