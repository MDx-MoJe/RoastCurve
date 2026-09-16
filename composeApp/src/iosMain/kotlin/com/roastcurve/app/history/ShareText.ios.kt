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

package com.roastcurve.app.history

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

@OptIn(ExperimentalForeignApi::class)
actual fun shareText(filename: String, content: String) {
    val items = listOf("$filename\n\n$content")
    val controller = UIActivityViewController(items, null)
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController
    root?.presentViewController(controller, true, null)
}
actual fun exportBackupToDownloads(filename: String, content: String): String? = null

actual fun exportBackupToDownloads(filename: String, data: ByteArray): String? = null

actual fun packBackupZip(filename: String, json: String): ByteArray? = null

actual fun unpackBackupZip(data: ByteArray): String? =
    data.decodeToString().takeIf { it.startsWith("{") }
