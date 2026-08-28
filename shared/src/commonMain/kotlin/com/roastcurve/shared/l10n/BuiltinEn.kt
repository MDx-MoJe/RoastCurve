package com.roastcurve.shared.l10n

/**
 * 内置英文包（随 App 分发）：生成器产出 EnBuilt，与手写基础键合并。
 */
object BuiltinEn {
    val strings: Map<String, String> by lazy {
        buildMap {
            putAll(EnBuilt.strings)
            put("app.name", "RoastCurve")
            put("common.ok", "OK")
            put("common.cancel", "Cancel")
            put("common.save", "Save")
            put("common.back", "Back")
        }
    }
}
