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

import com.roastcurve.shared.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 应用设置仓库（单个 JSON 文件：<storageDir>/settings.json）
 */
class SettingsStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val file get() = "${appStorageDir()}/settings.json"

    suspend fun load(): Settings = withContext(Dispatchers.IO) {
        try {
            json.decodeFromString<Settings>(readFile(file))
        } catch (_: Exception) {
            Settings()   // 首次运行或文件损坏时回退默认值
        }
    }

    suspend fun save(settings: Settings): Unit = withContext(Dispatchers.IO) {
        ensureDir(appStorageDir())
        writeFile(file, json.encodeToString(settings))
    }
}
