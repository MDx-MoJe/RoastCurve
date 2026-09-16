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

package com.roastcurve.app.platform

import android.app.Activity
import android.content.Context
import android.os.Build

actual fun exitApplication() {
    val activity = com.roastcurve.app.platform.ActivityHolder.activity ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        activity.finishAndRemoveTask()
    } else {
        activity.finish()
    }
}

/** MainActivity onCreate 时注册自身，供 Compose 层退出时使用 */
object ActivityHolder {
    @Volatile
    var activity: Activity? = null
}

/** 给 MainActivity 调用的一行注册助手 */
fun Context.registerForExit() {
    if (this is Activity) ActivityHolder.activity = this
}
