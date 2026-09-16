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

package com.roastcurve.app.settings

import android.app.Activity
import android.content.Intent

private var pending: ((ByteArray?) -> Unit)? = null

actual fun pickLangFile(callback: (ByteArray?) -> Unit) {
    val activity = com.roastcurve.shared.AppDirs.androidContext as? Activity
    if (activity == null) { callback(null); return }
    pending = callback
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/json"))
    }
    try {
        activity.startActivityForResult(i, REQ_PICK_LANG)
    } catch (_: Exception) {
        pending = null
        callback(null)
    }
}

const val REQ_PICK_LANG = 4201

/** MainActivity.onActivityResult 转发入口 */
fun handleLangPickResult(bytes: ByteArray?) {
    pending?.invoke(bytes)
    pending = null
}
