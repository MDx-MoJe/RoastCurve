package com.roastcurve.shared

/**
 * 返回键拦截钩子
 *
 * Android MainActivity.onBackPressed 每次查询：
 * handler 非 null 时把返回键交给它（典型用途：关闭浮层而非退出应用）；
 * 为 null 则走系统默认行为。
 */
object BackPressHook {
    @Volatile
    var handler: (() -> Unit)? = null
}

/**
 * 备份文件导入桥：设置页发起系统文件选择器（Android 端实现），
 * 选中的文本内容由 onPicked 回调带回（null = 用户取消）。
 */
object BackupBridge {
    var requestPick: (() -> Unit)? = null

    /** bytes=文件内容(null=取消)；nameHint=原始文件名（用于推导模板名） */
    var onPicked: ((ByteArray?, String?) -> Unit)? = null
}
