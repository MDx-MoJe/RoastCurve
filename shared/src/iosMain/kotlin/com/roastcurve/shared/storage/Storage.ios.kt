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

package com.roastcurve.shared.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
internal actual fun appStorageDir(): String {
    val docs = NSFileManager.defaultManager.URLForDirectory(
        NSDocumentDirectory, NSUserDomainMask, null, true, null
    ) as NSURL?
    return (docs?.path ?: NSHomeDirectory()) + "/roasts_data"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun ensureDir(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path, true, null, null
    )
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeFile(path: String, content: String) {
    val data = content.encodeToByteArray().toNSData()
    // atomically=true：先写临时文件再替换，防写一半崩溃留下截断 JSON
    if (!data.writeToFile(path, true)) {
        // 失败退化：createFileAtPath 兜底（原行为）
        NSFileManager.defaultManager.createFileAtPath(path, data, null)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun readFile(path: String): String {
    val data = NSFileManager.defaultManager.contentsAtPath(path)
        ?: throw IllegalStateException("cannot read $path")
    return data.toByteArray().decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun listFiles(dir: String): List<String> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)
        ?.filterIsInstance<String>() ?: emptyList()