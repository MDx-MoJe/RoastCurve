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

package com.roastcurve.shared.l10n

/**
 * 语言包导入导出（commonMain 契约）
 * - zip 容器：lang.zip 内含 lang.json
 * - 平台实现负责解 zip、读文件；解析与回退逻辑在 L10n
 */
interface LangPackIO {
    /** 从用户选择的 zip/json 文件字节导入并应用 */
    suspend fun importFrom(bytes: ByteArray): Result<String>

    /** 导出当前语言包为 zip 字节（给社区改翻译的底稿） */
    suspend fun exportCurrent(): ByteArray?

    /** 已保存的语言包列表（文件名） */
    suspend fun listSaved(): List<String>

    /** 加载已保存的语言包 */
    suspend fun loadSaved(fileName: String): Result<String>
}

expect fun langPackIO(): LangPackIO
