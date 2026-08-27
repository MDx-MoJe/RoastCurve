package com.roastcurve.app.platform

/**
 * 打开外部链接（平台实现）
 * Android：Intent ACTION_VIEW 调起浏览器
 * iOS：UIApplication.openURL 调起 Safari
 */
expect fun openUrl(url: String)
