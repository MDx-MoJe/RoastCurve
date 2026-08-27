package com.roastcurve.shared.storage

import com.roastcurve.shared.model.RoastRecord
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 烘焙记录仓库（JSON 文件存储）
 * 每炉一个文件：<storageDir>/roasts/<id>.json
 *
 * 选择文件而非数据库的理由：
 * 个人用户量级（年数百炉）、导出/备份/与 CoffeeBeanTracker 互通天然友好
 */
class RoastStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val dir get() = "${appStorageDir()}/roasts"

    private fun fileFor(id: String) = "$dir/$id.json"

    /** 保存（新建或覆盖） */
    suspend fun save(record: RoastRecord): Unit = withContext(Dispatchers.IO) {
        ensureDir(dir)
        writeFile(fileFor(record.id), json.encodeToString(record))
    }

    /** 全部记录，按 id（时间戳）倒序 */
    suspend fun listAll(): List<RoastRecord> = withContext(Dispatchers.IO) {
        ensureDir(dir)
        listFiles(dir)
            .filter { it.endsWith(".json") }
            .mapNotNull { name ->
                try {
                    json.decodeFromString<RoastRecord>(readFile("$dir/$name"))
                } catch (_: Exception) {
                    null   // 单文件损坏不影响整体
                }
            }
            .sortedByDescending { it.id }
    }

    suspend fun load(id: String): RoastRecord? = withContext(Dispatchers.IO) {
        try {
            json.decodeFromString<RoastRecord>(readFile(fileFor(id)))
        } catch (_: Exception) {
            null
        }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        deleteFile(fileFor(id))
    }

    companion object {
        /** 由 id 生成 CSV 文本（Artisan 可导入的通用三列格式） */
        fun toCsv(record: RoastRecord): String = buildString {
            appendLine("time_sec,bean_temp_c,env_temp_c")
            record.curveData.forEach { p ->
                val bt10 = (p.bt * 10).toInt()
                val btStr = "${bt10 / 10}.${(bt10 % 10).toInt().toString().takeLast(1)}"
                val etStr = p.et?.let { e ->
                    val e10 = (e * 10).toInt()
                    "${e10 / 10}.${e10 % 10}"
                } ?: ""
                appendLine("${p.timeSeconds.toInt()},$btStr,$etStr")
            }
        }

        /** 记录 id：时间戳形式 20260824-142500 */
        fun newId(epochMillis: Long): String {
            // 必须转本地时区：Instant.toString() 是 UTC，直接取子串会偏移时区
            val dt = Instant.fromEpochMilliseconds(epochMillis)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            fun p2(n: Int) = n.toString().padStart(2, '0')
            val date = dt.year.toString().padStart(4, '0') + p2(dt.monthNumber) + p2(dt.dayOfMonth)
            val time = p2(dt.hour) + p2(dt.minute) + p2(dt.second)
            return "$date-$time"
        }
    }
}