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
            put("settings.language_section", "Language")
            put("phase.drying", "Drying")
            put("phase.maillard", "Maillard")
            put("phase.development", "Development")
            put("common.back2", "Back")
            put("settings.export_zip", "Export Backup (zip)")
            put("settings.current_lang", "Language")
            put("settings.import_pack", "Import Pack")
            put("settings.export_current", "Export Current")
            put("settings.apply", "Apply")
            put("manual.title", "User Manual")
            put("manual.setting_autofollow", "Auto-follow on charge")
            put("manual.setting_autoconnect", "Auto-connect on launch")
            put("manual.setting_lookahead", "Lookahead")
            put("manual.setting_lookahead_desc", "SV looks ahead N seconds to compensate roaster lag (0=off)")
            put("manual.setting_darkmode", "Dark roast mode (2nd crack)")
            put("settings.manual", "User Manual")
            put("common.settings", "Settings")
            put("common.import", "Import")
            put("common.export", "Export")
            put("monitor.import_failed", "Import failed: {msg}")
            put("monitor.diag", "Diag: target {tgt}° · SV {sv}° · clock {clk}s")
        }
    }
}
