package com.roastcurve.app.settings

/**
 * 语言包文件选择桥：平台实现弹系统选择器，回传字节（单次）
 */
expect fun pickLangFile(callback: (ByteArray?) -> Unit)
