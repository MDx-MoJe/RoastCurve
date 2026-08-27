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
