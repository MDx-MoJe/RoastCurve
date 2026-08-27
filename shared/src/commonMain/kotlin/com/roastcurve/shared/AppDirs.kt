package com.roastcurve.shared

/**
 * 平台上下文注入
 * - androidMain: MainActivity 初始化 filesDir + androidContext
 * - iosMain: 初始化 documentsDir
 */
object AppDirs {
    lateinit var filesDir: String
        internal set

    /** Android 专用：用于分享等需要 Context 的场景 */
    var androidContext: Any? = null
    var appVersion: String = "?"

    fun init(filesDir: String, androidContext: Any? = null) {
        this.filesDir = filesDir
        this.androidContext = androidContext
    }
}
