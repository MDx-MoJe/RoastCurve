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
