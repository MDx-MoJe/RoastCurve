package com.roastcurve.app.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 跨平台数字格式化工具
 * Kotlin/Native 无 String.format，手写实现
 */

/** 保留一位小数："185.3" */
fun Float.toFixed1(): String {
    val scaled = (this * 10).roundToInt()
    val sign = if (scaled < 0) "-" else ""
    val v = abs(scaled)
    return "$sign${v / 10}.${v % 10}"
}

/** 秒转 m:ss 时间标签："7:05" */
fun Int.toTimeLabel(): String {
    val m = this / 60
    val s = this % 60
    return if (s < 10) "$m:0$s" else "$m:$s"
}