package com.roastcurve.app.platform

/** iOS 无需前台服务保活（无国产系统杀后台问题），空实现 */
actual fun keepAliveStart() {}

actual fun keepAliveStop() {}
