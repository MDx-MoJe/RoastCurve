package com.roastcurve.app.platform

import android.app.Activity
import android.content.Context
import android.os.Build

actual fun exitApplication() {
    val activity = com.roastcurve.app.platform.ActivityHolder.activity ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        activity.finishAndRemoveTask()
    } else {
        activity.finish()
    }
}

/** MainActivity onCreate 时注册自身，供 Compose 层退出时使用 */
object ActivityHolder {
    @Volatile
    var activity: Activity? = null
}

/** 给 MainActivity 调用的一行注册助手 */
fun Context.registerForExit() {
    if (this is Activity) ActivityHolder.activity = this
}
