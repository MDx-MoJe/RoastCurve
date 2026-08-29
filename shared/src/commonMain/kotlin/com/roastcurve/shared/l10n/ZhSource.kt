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
            put("common.back2", "返回")
            put("settings.export_zip", "导出备份 (zip)")
            put("settings.current_lang", "当前语言")
            put("settings.import_pack", "导入语言包")
            put("settings.export_current", "导出当前")
            put("settings.apply", "应用")
            put("manual.s70", "七、语言包")
            put("manual.s71", "设置 → 语言 / Language 里可切换内置的中文/English，或导入语言包 zip（1 KB 左右的小文件）。选择后点「应用」，重启 App 即生效。")
            put("manual.s72", "社区翻译语言包可在 GitHub Releases 下载，也可以自己照格式翻译一份导入——缺词自动回退中文，不怕翻一半。")
            put("manual.title", "使用手册")
            put("manual.setting_autofollow", "入豆自动开始跟随")
            put("manual.setting_autoconnect", "启动时自动连接")
            put("manual.setting_lookahead", "跟随前瞻")
            put("manual.setting_lookahead_desc", "SV 提前参考 N 秒后的目标值，补偿炉子热惯性（0=关闭）")
            put("manual.setting_darkmode", "深烘模式（显示二爆）")
            put("settings.manual", "使用手册")
            put("common.settings", "设置")
            put("common.import", "导入")
            put("common.export", "导出")
            put("monitor.import_failed", "导入失败：{msg}")
            put("monitor.diag", "诊断：目标 {tgt}° · 回读SV {sv}° · 模板时钟 {clk}s")
        }
    }
}
