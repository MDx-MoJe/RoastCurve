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
                // locale 无关的定点 1 位小数（"%.1f".format 走系统 locale，de/fr 出逗号破坏 CSV）；
                // 负温度符号单独处理（旧整数除法 -0.5 → "0.-5" 畸形）
                fun fmt1(v: Float): String {
                    val n = Math.round(v * 10f)
                    val neg = n < 0
                    val a = kotlin.math.abs(n)
                    return (if (neg) "-" else "") + "${a / 10}.${a % 10}"
                }
                appendLine("${p.timeSeconds.toInt()},${fmt1(p.bt)},${p.et?.let { fmt1(it) } ?: ""}")
            }
        }

        /** 记录 id：时间戳形式 20260824-142500-123（追加毫秒防同秒两炉冲突） */
        fun newId(epochMillis: Long): String {
            // 必须转本地时区：Instant.toString() 是 UTC，直接取子串会偏移时区
            val dt = Instant.fromEpochMilliseconds(epochMillis)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            fun p2(n: Int) = n.toString().padStart(2, '0')
            val date = dt.year.toString().padStart(4, '0') + p2(dt.monthNumber) + p2(dt.dayOfMonth)
            val time = p2(dt.hour) + p2(dt.minute) + p2(dt.second)
            val ms = p2((epochMillis % 1000L).toInt() / 10)   // 厘秒精度足够防同秒冲突，id 不过长
            return "$date-$time-$ms"
        }
    }
}