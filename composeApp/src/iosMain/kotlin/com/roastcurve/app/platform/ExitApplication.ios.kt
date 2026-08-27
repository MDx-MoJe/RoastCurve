package com.roastcurve.app.platform

actual fun exitApplication() {
    // iOS 无"应用主动退出"惯例（会被视为异常行为），空实现：
    // 用户看到提示后自行上滑退出即可
}
