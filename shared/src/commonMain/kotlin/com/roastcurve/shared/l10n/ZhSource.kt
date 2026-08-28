package com.roastcurve.shared.l10n

/**
 * 内置中文源（源语言）：生成器产出 ZhSourceGenerated，与手写基础键合并。
 */
object ZhSource {
    val strings: Map<String, String> by lazy {
        buildMap {
            putAll(ZhSourceGenerated.strings)
            // 手写基础键（语言包工具元数据用）
            put("app.name", "烤豆")
            put("common.ok", "知道了")
            put("common.cancel", "取消")
            put("common.save", "保存")
            put("common.back", "返回")
            put("settings.language_section", "语言 / Language")
            put("phase.drying", "脱水")
            put("phase.maillard", "美拉德")
            put("phase.development", "发展")
            put("common.settings", "设置")
            put("common.import", "导入")
            put("common.export", "导出")
            put("monitor.import_failed", "导入失败：{msg}")
            put("monitor.diag", "诊断：目标 {tgt}° · 回读SV {sv}° · 模板时钟 {clk}s")
        }
    }
}
