package com.roastcurve.app.platform

import android.content.Intent
import android.net.Uri

actual fun openUrl(url: String) {
    val activity = ActivityHolder.activity ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}
