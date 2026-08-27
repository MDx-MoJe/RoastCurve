package com.roastcurve.shared

/**
 * iOS 侧 AppDirs（iosMain 提供 actual 行为）
 */
object IosAppDirs {
    fun init(documentsDir: String) {
        AppDirs.init(documentsDir)
    }
}