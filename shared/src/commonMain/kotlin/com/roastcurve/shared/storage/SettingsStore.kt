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
