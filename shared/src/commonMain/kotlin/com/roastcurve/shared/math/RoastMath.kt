package com.roastcurve.shared.math

import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent
import com.roastcurve.shared.model.RoastPhase
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 烘焙曲线数学计算工具
 * 包含 RoR 计算、平滑、阶段检测等核心算法
 */
object RoastMath {

    /**
     * 计算升温速率 RoR（°C/min）
     * 使用中心差分法，默认窗口为前后各 15 秒
     *
     * @param points 曲线数据点列表
     * @param windowSeconds 差分窗口大小（秒），默认 15
     * @return 每个点的 RoR 值
     */
    fun calculateRoR(
        points: List<CurvePoint>,
        windowSeconds: Float = 15f,
    ): List<Float> {
        if (points.size < 3) return points.map { 0f }

        return points.mapIndexed { i, point ->
            // 找到窗口边界
            val leftIdx = points.binarySearchBy(point.timeSeconds - windowSeconds) { it.timeSeconds }
                .let { if (it < 0) -it - 1 else it }
            val rightIdx = points.binarySearchBy(point.timeSeconds + windowSeconds) { it.timeSeconds }
                .let { if (it < 0) -it - 2 else it }
                .coerceAtMost(points.lastIndex)

            val leftPoint = points[max(leftIdx, 0)]
            val rightPoint = points[rightIdx]

            val dt = rightPoint.timeSeconds - leftPoint.timeSeconds
            if (dt >= 1f) {   // 时间窗过小会导致 RoR 爆炸，跳过
                ((rightPoint.bt - leftPoint.bt) / dt) * 60f  // 转换为 °C/min
            } else {
                0f
            }
        }
    }

    /**
     * 模板目标温度插值：返回 t 时刻的线性插值
     * t 超出曲线末尾时返回 null（视为跟随结束）；开头之前取首点
     */
    fun profileTargetAt(points: List<CurvePoint>, t: Float): Float? {
        if (points.isEmpty()) return null
        if (t <= points.first().timeSeconds) return points.first().bt
        if (t >= points.last().timeSeconds) return null
        var lo = 0
        var hi = points.lastIndex
        while (hi - lo > 1) {                      // 二分找右邻
            val mid = (lo + hi) / 2
            if (points[mid].timeSeconds <= t) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = b.timeSeconds - a.timeSeconds
        if (span <= 0f) return b.bt
        val k = (t - a.timeSeconds) / span
        return a.bt + (b.bt - a.bt) * k
    }

    /**
     * 风速曲线目标：从风速锚点线性插值（锚点 bt 字段存风速 0-100%）
     * 语义与温度目标一致：开头之前取首点，超出末尾返回 null
     * 锚点为空返回 null（模板无风速曲线 → 跟随不动风速）
     */
    fun fanTargetAt(anchors: List<com.roastcurve.shared.model.AnchorPoint>, t: Float): Float? {
        if (anchors.isEmpty()) return null
        return profileTargetAt(anchors.map { CurvePoint(it.timeSeconds, it.bt.coerceIn(0f, 100f)) }, t)
    }

    /**
     * 从曲线采样点提取风速锚点（存为模板用）：
     * 取 fanDuty 非空且风速变化 ≥2% 的点 + 首点，压缩成稀疏锚点列表
     */
    fun fanAnchorsFrom(points: List<CurvePoint>): List<com.roastcurve.shared.model.AnchorPoint> {
        val out = mutableListOf<com.roastcurve.shared.model.AnchorPoint>()
        var lastFan = -1f
        for (p in points) {
            val f = p.fanDuty ?: continue
            if (f < 0f) continue
            if (out.isEmpty() || kotlin.math.abs(f - lastFan) >= 2f) {
                out.add(com.roastcurve.shared.model.AnchorPoint(p.timeSeconds, f))
                lastFan = f
            }
        }
        return out
    }

    /**
     * SV 平滑（Artisan 同款）：最近 n 个目标值的衰减加权平均，权重 1..n
     * 越新权重越大，把模板的台阶抹成缓坡，对应 Artisan 的 smooth_sv()
     */
    fun smoothSv(history: List<Float>): Float {
        if (history.isEmpty()) return 0f
        var num = 0f
        var den = 0f
        for ((i, v) in history.withIndex()) {
            val w = i + 1f
            num += v * w
            den += w
        }
        return num / den
    }

    /**
     * 为曲线点补算 RoR（历史回看/导出用）
     * 与实时路径同公式同参数，保证监控与详情页曲线一致
     */
    fun withRor(points: List<CurvePoint>, alpha: Float = 0.35f): List<CurvePoint> {
        if (points.size < 3) return points
        val ror = smoothEMA(calculateRoR(points), alpha)
        return points.mapIndexed { i, p -> p.copy(ror = ror.getOrElse(i) { 0f }) }
    }

    /**
     * 指数移动平均平滑
     * 用于降低 RoR 曲线的噪声
     *
     * @param values 原始值
     * @param alpha 平滑因子 (0-1)，越小越平滑，默认 0.1
     */
    fun smoothEMA(values: List<Float>, alpha: Float = 0.1f): List<Float> {
        if (values.isEmpty()) return emptyList()
        val result = mutableListOf(values.first())
        for (i in 1 until values.size) {
            result.add(alpha * values[i] + (1 - alpha) * result.last())
        }
        return result
    }

    /**
     * 检测烘焙阶段
     *
     * 基于温度阈值和 RoR 变化识别：
     * - DRY（干燥结束）：BT ≈ 150°C，RoR 开始下降后回升
     * - FCs（一爆开始）：BT ≈ 190-200°C，RoR 急剧上升
     * - FCe（一爆结束）：RoR 从峰值回落
     * - SCs（二爆开始）：BT ≈ 220-225°C，RoR 再次上升
     */
    fun detectPhase(
        bt: Float,
        ror: Float,
        elapsedSeconds: Float,
        previousPhase: RoastPhase,
    ): RoastPhase {
        return when {
            elapsedSeconds < 30f -> RoastPhase.PREHEAT
            bt < 150f -> RoastPhase.DRYING
            bt < 195f -> RoastPhase.MAILLARD
            else -> RoastPhase.DEVELOPMENT
        }
    }

    /**
     * 计算发展时间占比 DTR（Development Time Ratio）
     * DTR = (DROP 时间 - FCs 时间) / 总烘焙时间
     */
    fun calculateDTR(events: List<EventMarker>, totalTimeSeconds: Float): Float {
        val fcs = events.find { it.event == RoastEvent.FCs }?.timeSeconds ?: return 0f
        val drop = events.find { it.event == RoastEvent.DROP }?.timeSeconds ?: totalTimeSeconds
        return if (totalTimeSeconds > 0) (drop - fcs) / totalTimeSeconds else 0f
    }

    /**
     * 计算烘焙损失率
     * Weight Loss = (入豆重 - 出豆重) / 入豆重 × 100%
     */
    fun calculateWeightLoss(chargeWeight: Float, dropWeight: Float): Float {
        return if (chargeWeight > 0) ((chargeWeight - dropWeight) / chargeWeight) * 100f else 0f
    }

    /**
     * 对曲线数据进行降采样
     * 用于导出和存储时减少数据量
     */
    fun downsample(points: List<CurvePoint>, targetPoints: Int = 600): List<CurvePoint> {
        if (points.size <= targetPoints) return points
        val step = points.size.toFloat() / targetPoints
        return (0 until targetPoints).map { i ->
            points[(i * step).toInt().coerceAtMost(points.lastIndex)]
        }
    }

    /**
     * 锚点 → 平滑目标曲线（Fritsch-Carlson 单调三次插值）
     *
     * 自定义模板编辑器的核心：少量锚点生成密集采样点列，
     * 形态自然无过冲（相邻区间温度不反向抖动），
     * 产出的 points 与历史提取的模板同构，跟随控制器零改动。
     *
     * @param anchors 锚点（自动按时间排序、去重同时刻）
     * @param stepSec 采样步长秒
     */
    fun anchorsToPoints(anchors: List<com.roastcurve.shared.model.AnchorPoint>, stepSec: Float = 5f): List<CurvePoint> {
        // 排序 + 同时刻去重（保留后者），统一转成纯数值点列
        val sorted = anchors.sortedBy { it.timeSeconds }.map { CurvePoint(it.timeSeconds, it.bt) }
        val a = ArrayList<CurvePoint>(sorted.size)
        for (p in sorted) {
            if (a.isNotEmpty() && p.timeSeconds == a.last().timeSeconds) a[a.lastIndex] = p
            else a.add(p)
        }
        if (a.size < 2) return a.toList()

        val n = a.size
        val dx = FloatArray(n - 1) { a[it + 1].timeSeconds - a[it].timeSeconds }
        val slope = FloatArray(n - 1) { (a[it + 1].bt - a[it].bt) / dx[it] }

        // 切线初值：端点取单侧斜率，内部取均值（变号处置 0 → 局部极值平台）
        val m = FloatArray(n)
        m[0] = slope[0]
        m[n - 1] = slope[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
        }
        // FC 限制器：切线超过割线3倍则收缩，保证单调不过冲
        for (i in 0 until n - 1) {
            val s = slope[i]
            if (s == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val tauI = m[i] / s
            val tauJ = m[i + 1] / s
            val excess = maxOf(kotlin.math.abs(tauI), kotlin.math.abs(tauJ))
            if (excess > 3f) {
                m[i] = tauI * s * 3f / excess
                m[i + 1] = tauJ * s * 3f / excess
            }
        }

        // Hermite 基函数逐段采样
        val out = ArrayList<CurvePoint>()
        var seg = 0
        var t = a[0].timeSeconds
        while (t <= a[n - 1].timeSeconds + 0.01f) {
            while (seg < n - 2 && t > a[seg + 1].timeSeconds) seg++
            val p0 = a[seg]; val p1 = a[seg + 1]
            val h = p1.timeSeconds - p0.timeSeconds
            val u = if (h > 0f) ((t - p0.timeSeconds) / h).coerceIn(0f, 1f) else 1f
            val u2 = u * u
            val u3 = u2 * u
            val h00 = 2 * u3 - 3 * u2 + 1f
            val h10 = u3 - 2 * u2 + u
            val h01 = -2 * u3 + 3 * u2
            val h11 = u3 - u2
            val bt = h00 * p0.bt + h10 * h * m[seg] + h01 * p1.bt + h11 * h * m[seg + 1]
            out.add(CurvePoint(timeSeconds = t, bt = bt))
            t += stepSec
        }
        // 确保终点精确收尾
        if (out.lastOrNull()?.timeSeconds ?: -1f < a[n - 1].timeSeconds - 0.01f) {
            out.add(a[n - 1])
        } else {
            out[out.lastIndex] = a[n - 1]
        }
        return out
    }
}