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

package com.roastcurve.shared.diag

import com.roastcurve.shared.storage.appStorageDir
import com.roastcurve.shared.storage.ensureDir
import com.roastcurve.shared.storage.readFile
import com.roastcurve.shared.storage.writeFile
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 连接诊断日志（常开，环形）
 *
 * 分工：
 *   - 内存环形 400 条：诊断页实时展示，App 重启即丢
 *   - 落盘 diag/diag.log：只记「连接级」事件，跨重启保留，供导出诊断包
 *
 * 落盘策略：连接类事件低频（一次连接尝试 3 条以内、轮询连续失败才记一条），
 * 不记录每秒采样，避免 IO 风暴把烘焙本身拖慢。
 */
object DiagLog {

    enum class Level(val tag: String) { INFO("I"), WARN("W"), ERROR("E") }

    data class Entry(
        val epochMs: Long,
        val level: Level,
        val scope: String,     // CONNECT / POLL / WRITE / DIAG
        val message: String,
    )

    private const val MAX_MEM = 400
    private const val MAX_FILE_CHARS = 200_000

    // commonMain 无同步原语；写入点都是低频事件，并发最多丢一条，可接受
    @Volatile
    private var buf: List<Entry> = emptyList()

    @Volatile
    var enabled: Boolean = true

    private fun filePath() = "${appStorageDir()}/diag/diag.log"

    fun info(scope: String, message: String) = append(Level.INFO, scope, message)
    fun warn(scope: String, message: String) = append(Level.WARN, scope, message)
    fun error(scope: String, message: String) = append(Level.ERROR, scope, message)

    fun append(level: Level, scope: String, message: String) {
        if (!enabled) return
        val e = Entry(Clock.System.now().toEpochMilliseconds(), level, scope, message)
        buf = (buf + e).takeLast(MAX_MEM)
        // 落盘只留 WARN/ERROR + CONNECT 全量
        if (level != Level.INFO || scope == "CONNECT" || scope == "DIAG") {
            appendToFile(e)
        }
    }

    private fun appendToFile(e: Entry) {
        try {
            ensureDir("${appStorageDir()}/diag")
            val path = filePath()
            val old = try { readFile(path) } catch (_: Exception) { "" }
            val line = "${format(e.epochMs)} ${e.level.tag} ${e.scope} ${e.message}\n"
            val next = if (old.length + line.length > MAX_FILE_CHARS) {
                // 超出上限：砍掉前 40%，保留近期
                old.substring((old.length * 0.4).toInt()) + line
            } else old + line
            writeFile(path, next)
        } catch (_: Exception) {
            // 落盘失败不影响主流程
        }
    }

    /** 内存中的最近记录（UI 用） */
    fun recent(limit: Int = MAX_MEM): List<Entry> = buf.takeLast(limit)

    /** 落盘的完整历史（可空） */
    fun persisted(): String = try { readFile(filePath()) } catch (_: Exception) { "" }

    /** 清空（内存 + 落盘） */
    fun clear() {
        buf = emptyList()
        try { writeFile(filePath(), "") } catch (_: Exception) {}
    }

    /** 导出诊断日志正文：落盘历史 + 本进程内存补足 */
    fun dump(): String {
        val disk = persisted()
        val mem = buf.joinToString("") {
            "${format(it.epochMs)} ${it.level.tag} ${it.scope} ${it.message}\n"
        }
        return if (disk.isBlank()) {
            "# 诊断日志（本进程）\n$mem"
        } else {
            "# 诊断日志（落盘历史 + 本进程）\n$disk\n# --- 本进程 ---\n$mem"
        }
    }

    private fun format(epochMs: Long): String {
        val dt = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        fun p2(v: Int) = v.toString().padStart(2, '0')
        return "${dt.year}-${p2(dt.monthNumber)}-${p2(dt.dayOfMonth)} " +
            "${p2(dt.hour)}:${p2(dt.minute)}:${p2(dt.second)}." +
            epochMs.mod(1000).toString().padStart(3, '0')
    }
}
