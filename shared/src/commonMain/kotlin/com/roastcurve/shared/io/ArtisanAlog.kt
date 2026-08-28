package com.roastcurve.shared.io

import com.roastcurve.shared.l10n.L10n
import com.roastcurve.shared.model.CurvePoint

/**
 * Artisan .alog 文件解析器（最小实现）
 *
 * .alog 本体是 Python 字典字面量文本，曲线数据在
 *   'timex': [秒...] 与 'temp1': [豆温...]（temp2 为炉温）
 * 数值为纯数字列表，可安全切片提取。
 */
object ArtisanAlog {

    data class Result(
        val title: String?,
        val pointsSec: List<Float>,
        val bt: List<Float>,
        val phases: List<Float> = emptyList(),   // 作者定义的阶段温度分界
    )

    /** 解析失败返回 null */
    fun parseBt(content: String): Result? {
        val timex = floatList(content, "timex")
        val bt = floatList(content, "temp1")
        if (timex.isEmpty() || bt.size != timex.size) return null
        val phases = floatList(content, "phases")
        val tm = Regex("'title':\\s*'([^']*)'").find(content)
        val rawTitle = tm?.groupValues?.get(1)
        val title = rawTitle?.let { unescapeUnicode(it) }
            ?.takeIf { it.isNotBlank() && it != L10n.get("app.s9") }
        return Result(title, timex, bt, phases)
    }

    /** 每 stepSec 秒取一个点（源数据 1s 间隔），生成标准曲线 */
    fun toPoints(r: Result, btKey: (Float) -> Float = { it }, stepSec: Float = 2f): List<CurvePoint> {
        val out = ArrayList<CurvePoint>()
        var next = 0f
        for ((t, b) in r.pointsSec.zip(r.bt)) {
            if (t >= next && b >= 0f) {
                out.add(CurvePoint(timeSeconds = t, bt = btKey(b)))
                next += stepSec
            }
        }
        return out
    }

    /**
     * 提取 .alog 自带的精确事件节点（来自 computed 字典）。
     * 依次尝试：入豆 CHARGE / 脱水结束 DRY / 一爆 FCs / 出豆 DROP，
     * 每个节点含精确时间与温度；缺失的键跳过。
     */
    fun parseComputedEvents(content: String): List<com.roastcurve.shared.model.AnchorPoint> {
        val out = ArrayList<com.roastcurve.shared.model.AnchorPoint>()
        val chargeBt = floatField(content, "CHARGE_BT")
        if (chargeBt != null) {
            out.add(com.roastcurve.shared.model.AnchorPoint(0f, chargeBt, "入豆"))
        }
        val dryT = floatField(content, "DRY_time")
        val dryBt = floatField(content, "DRY_BT")
        if (dryT != null && dryBt != null) {
            out.add(com.roastcurve.shared.model.AnchorPoint(dryT, dryBt, "脱水"))
        }
        val fcsT = floatField(content, "FCs_time")
        val fcsBt = floatField(content, "FCs_BT")
        if (fcsT != null && fcsBt != null) {
            out.add(com.roastcurve.shared.model.AnchorPoint(fcsT, fcsBt, "一爆"))
        }
        val dropT = floatField(content, "DROP_time")
        val dropBt = floatField(content, "DROP_BT")
        if (dropT != null && dropBt != null) {
            out.add(com.roastcurve.shared.model.AnchorPoint(dropT, dropBt, "出豆"))
        }
        return out
    }

    /**
     * 综合推导锚点：优先 .alog 自带 computed 精确事件节点，
     * 缺失时回退到 phases 阶段温度阈值推导；两端兜底为首点/末点。
     */
    fun deriveAnchors(
        points: List<com.roastcurve.shared.model.CurvePoint>,
        phases: List<Float>,
        computed: List<com.roastcurve.shared.model.AnchorPoint> = emptyList(),
    ): List<com.roastcurve.shared.model.AnchorPoint> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<com.roastcurve.shared.model.AnchorPoint>()
        val f = points.first()
        val l = points.last()

        fun firstCross(target: Float): com.roastcurve.shared.model.CurvePoint? =
            points.firstOrNull { it.bt >= target }

        // 入豆：优先精确节点，否则首点
        out.add(computed.firstOrNull { it.label == "入豆" }
            ?: com.roastcurve.shared.model.AnchorPoint(f.timeSeconds, f.bt, "入豆"))

        // 脱水
        val dry = computed.firstOrNull { it.label == "脱水" }
        if (dry != null) {
            out.add(dry)
        } else if (phases.isNotEmpty()) {
            firstCross(phases[0])?.let { p ->
                out.add(com.roastcurve.shared.model.AnchorPoint(p.timeSeconds, p.bt, "脱水"))
            }
        }

        // 一爆
        val fc = computed.firstOrNull { it.label == "一爆" }
        if (fc != null) {
            out.add(fc)
        } else if (phases.size >= 3) {
            firstCross(phases[2])?.let { p ->
                out.add(com.roastcurve.shared.model.AnchorPoint(p.timeSeconds, p.bt, "一爆"))
            }
        }

        // 出豆：优先精确节点，否则末点
        out.add(computed.firstOrNull { it.label == "出豆" }
            ?: com.roastcurve.shared.model.AnchorPoint(l.timeSeconds, l.bt, "出豆"))

        return out.distinctBy { it.timeSeconds.toInt() }
    }


    /** 提取标量数值字段（如 computed 字典里的 'DRY_time': 180.0） */
    private fun floatField(content: String, key: String): Float? =
        Regex("'$key':\\s*([\\d.]+)").find(content)?.groupValues?.get(1)?.toFloatOrNull()

    private fun floatList(content: String, key: String): List<Float> {
        val k = content.indexOf("'$key'")
        if (k < 0) return emptyList()
        val s = content.indexOf('[', k)
        val e = content.indexOf(']', s)
        if (s < 0 || e <= s) return emptyList()
        return content.substring(s + 1, e).split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
    }

    private fun unescapeUnicode(s: String): String =
        Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { m ->
            Char(m.groupValues[1].toInt(16)).toString()
        }
}
