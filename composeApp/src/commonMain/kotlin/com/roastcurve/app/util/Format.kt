/*
 * RoastCurve（烤豆）—— 咖啡烘焙曲线记录与控制
 * Copyright 2026 MDx
 * https://github.com/MDx-MoJe/RoastCurve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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