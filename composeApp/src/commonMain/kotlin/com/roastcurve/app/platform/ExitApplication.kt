package com.roastcurve.app.platform

/**
 * 退出应用（平台实现）
 * Android：finishActivity 并把任务栈移出最近任务
 * iOS：iOS 无"退出应用"惯例，空实现（弹窗文案不改，用户自行上滑退出）
 */
expect fun exitApplication()
