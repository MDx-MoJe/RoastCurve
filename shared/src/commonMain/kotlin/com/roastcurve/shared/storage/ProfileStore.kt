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

import com.roastcurve.shared.model.RoastProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 烘焙模板仓库（JSON 文件存储）
 * 每个模板一个文件：<storageDir>/profiles/<id>.json
 * 与 RoastRecord 的存储约定一致，便于备份与迁移
 */
class ProfileStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val dir get() = "${appStorageDir()}/profiles"

    private fun fileFor(id: String) = "$dir/$id.json"

    /** 保存（新建或覆盖） */
    suspend fun save(profile: RoastProfile): Unit = withContext(Dispatchers.IO) {
        ensureDir(dir)
        writeFile(fileFor(profile.id), json.encodeToString(profile))
    }

    /** 全部模板，按 id（时间戳）倒序 */
    suspend fun listAll(): List<RoastProfile> = withContext(Dispatchers.IO) {
        ensureDir(dir)
        listFiles(dir)
            .filter { it.endsWith(".json") }
            .mapNotNull { name ->
                try {
                    json.decodeFromString<RoastProfile>(readFile("$dir/$name"))
                } catch (_: Exception) {
                    null   // 单文件损坏不影响整体
                }
            }
            .sortedByDescending { it.id }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        deleteFile(fileFor(id))
    }
}
