package com.roastcurve.shared.storage

import com.roastcurve.shared.model.BackupBundle
import kotlinx.serialization.json.Json

/**
 * 备份包编解码器：统一导出/导入的 JSON 格式
 * - 紧凑输出（encodeDefaults=false，跳过 null/空值）
 * - 忽略未知字段（新版本备份在旧版本 App 上导入不崩）
 */
object BackupCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    fun decode(text: String): BackupBundle = json.decodeFromString(BackupBundle.serializer(), text)
}
