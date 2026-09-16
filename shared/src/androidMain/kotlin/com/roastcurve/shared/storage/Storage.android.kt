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

import com.roastcurve.shared.AppDirs
import java.io.File

actual fun appStorageDir(): String = AppDirs.filesDir

internal actual fun ensureDir(path: String) {
    File(path).mkdirs()
}

internal actual fun writeFile(path: String, content: String) {
    // 原子写：先写同目录临时文件再 rename（rename 同目录内原子），
    // 防写一半被杀/断电留下截断 JSON（设置/会话/记录损坏 = 整炉或全部配置丢失）
    val tmp = File(path + ".tmp")
    tmp.writeText(content, Charsets.UTF_8)
    if (!tmp.renameTo(File(path))) {
        // rename 失败（罕见）：退化为直接写，避免写入完全丢失
        File(path).writeText(content, Charsets.UTF_8)
    }
}

internal actual fun readFile(path: String): String =
    File(path).readText(Charsets.UTF_8)

internal actual fun deleteFile(path: String) {
    File(path).delete()
}

internal actual fun listFiles(dir: String): List<String> =
    File(dir).listFiles()?.map { it.name } ?: emptyList()