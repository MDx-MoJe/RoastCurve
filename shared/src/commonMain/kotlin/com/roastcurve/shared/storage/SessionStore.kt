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

import com.roastcurve.shared.model.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 烘焙会话运行态仓库（单文件：<storageDir>/session.json）
 * 记录中定期写入，进程被杀后启动时据此恢复「记录中」状态。
 */
class SessionStore {

    private val json = Json { ignoreUnknownKeys = true }

    private val file get() = "${appStorageDir()}/session.json"

    suspend fun load(): SessionState = withContext(Dispatchers.IO) {
        try {
            json.decodeFromString<SessionState>(readFile(file))
        } catch (_: Exception) {
            SessionState()   // 首次运行或损坏回退默认
        }
    }

    suspend fun save(state: SessionState): Unit = withContext(Dispatchers.IO) {
        ensureDir(appStorageDir())
        writeFile(file, json.encodeToString(state))
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        try {
            deleteFile(file)
        } catch (_: Exception) {
        }
    }
}
