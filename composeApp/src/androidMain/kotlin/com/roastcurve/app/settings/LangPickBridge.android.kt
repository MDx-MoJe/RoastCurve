package com.roastcurve.app.settings

import android.app.Activity
import android.content.Intent

private var pending: ((ByteArray?) -> Unit)? = null

actual fun pickLangFile(callback: (ByteArray?) -> Unit) {
    val activity = com.roastcurve.shared.AppDirs.androidContext as? Activity
    if (activity == null) { callback(null); return }
    pending = callback
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/json"))
    }
    try {
        activity.startActivityForResult(i, REQ_PICK_LANG)
    } catch (_: Exception) {
        pending = null
        callback(null)
    }
}

const val REQ_PICK_LANG = 4201

/** MainActivity.onActivityResult 转发入口 */
fun handleLangPickResult(bytes: ByteArray?) {
    pending?.invoke(bytes)
    pending = null
}
