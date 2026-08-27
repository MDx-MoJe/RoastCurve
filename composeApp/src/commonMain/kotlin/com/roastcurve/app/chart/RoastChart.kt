package com.roastcurve.app.chart

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roastcurve.design.DarkRoast
import com.roastcurve.design.WarmBeige
import com.roastcurve.app.util.toFixed1
import com.roastcurve.app.util.toTimeLabel
import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent

/**
 * 烤豆 核心烘焙曲线图表
 *
 * 使用 Compose Canvas 手写渲染，支持：
 * - 实时曲线滚动（BT + ET + RoR）
 * - 背景曲线叠加
 * - 阶段着色（干燥/梅纳/发展）
 * - 事件标记（CHARGE/DRY/FC/SC/DROP）
 * - 双 Y 轴（温度 + RoR）
 * - 手势缩放和平移
 */
/** 图表绘图区内边距（px，绘制与手势换算共用） */
private const val PLOT_LEFT_PX = 60f
private const val PLOT_RIGHT_MARGIN = 60f
private const val PLOT_TOP_PX = 20f
private const val PLOT_BOTTOM_MARGIN = 40f

@Composable
fun RoastChart(
    data: ChartData,
    viewport: ChartViewport = ChartViewport(),
    modifier: Modifier = Modifier,
) {
    // 跟随系统主题切换调色板
    val palette = if (isSystemInDarkTheme()) DarkRoast else WarmBeige
    var currentViewport by remember { mutableStateOf(viewport) }
    var isAutoScroll by remember { mutableStateOf(viewport.autoScroll) }
    val textMeasurer = rememberTextMeasurer()

    // 自动滚动
    LaunchedEffect(data.liveCurve.lastOrNull()?.timeSeconds) {
        if (isAutoScroll && data.isRunning) {
            val latestTime = data.liveCurve.lastOrNull()?.timeSeconds ?: 0f
            val window = currentViewport.timeMax - currentViewport.timeMin
            if (latestTime > currentViewport.timeMax - window * 0.2f) {
                currentViewport = currentViewport.copy(
                    timeMin = (latestTime - window).coerceAtLeast(0f),
                    timeMax = latestTime + window * 0.1f,
                )
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBeige.Background)
            .pointerInput(Unit) {
                // 仅双指响应：捏合缩放时间轴 + 双指平移。
                // 单指事件不消费——页面滚动正常，也不会误关自动跟随。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var twoFinger = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            twoFinger = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()

                            val vpMin = currentViewport.timeMin
                            val range = currentViewport.timeMax - vpMin
                            val plotW = size.width - PLOT_LEFT_PX - PLOT_RIGHT_MARGIN
                            val anchorT = vpMin + ((centroid.x - PLOT_LEFT_PX) / plotW).coerceIn(0f, 1f) * range

                            var newRange = (range / zoom.coerceAtLeast(0.1f)).coerceIn(120f, 3600f)
                            var newMin = anchorT - (anchorT - vpMin) * (newRange / range)
                            newMin -= pan.x / plotW * newRange
                            var newMax = newMin + newRange
                            if (newMin < 0f) { newMax -= newMin; newMin = 0f }

                            currentViewport = currentViewport.copy(timeMin = newMin, timeMax = newMax)

                            // 视口右缘回到最新数据附近 → 自动恢复跟随
                            val latest = data.liveCurve.lastOrNull()?.timeSeconds ?: 0f
                            isAutoScroll = newMax >= latest - range * 0.05f

                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        val chartWidth = size.width
        val chartHeight = size.height

        val leftMargin = PLOT_LEFT_PX
        val rightMargin = PLOT_RIGHT_MARGIN
        val topMargin = PLOT_TOP_PX
        val bottomMargin = PLOT_BOTTOM_MARGIN

        val plotLeft = leftMargin
        val plotRight = chartWidth - rightMargin
        val plotTop = topMargin
        val plotBottom = chartHeight - bottomMargin
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        val vp = currentViewport

        fun timeToX(t: Float): Float = plotLeft + (t - vp.timeMin) / (vp.timeMax - vp.timeMin) * plotWidth
        fun tempToY(temp: Float): Float = plotBottom - (temp - vp.tempMin) / (vp.tempMax - vp.tempMin) * plotHeight
        fun rorToY(ror: Float): Float = plotBottom - (ror - vp.rorMin) / (vp.rorMax - vp.rorMin) * plotHeight

        val labelStyle = TextStyle(fontSize = 10.sp, color = palette.GridText)
        val boldLabelStyle = TextStyle(fontSize = 14.sp, color = palette.OnSurface)

        // 描边常量单位为 dp，按屏幕密度换算成像素：高分屏下曲线才够粗可见
        val curveStroke = ChartConfig.CURVE_STROKE_WIDTH.dp.toPx()
        val bgStroke = ChartConfig.BACKGROUND_STROKE_WIDTH.dp.toPx()
        val rorStroke = ChartConfig.ROR_STROKE_WIDTH.dp.toPx()
        val gridStroke = ChartConfig.GRID_STROKE_WIDTH.dp.toPx()
        val markerW = ChartConfig.EVENT_MARKER_WIDTH.dp.toPx()
        val markerH = ChartConfig.EVENT_MARKER_HEIGHT.dp.toPx()
        val dotR = 3.dp.toPx()

        // ===== 1. 阶段着色 =====
        if (vp.showPhases && data.events.isNotEmpty()) {
            val chargeEvent = data.events.find { it.event == RoastEvent.CHARGE }
            val dryEvent = data.events.find { it.event == RoastEvent.DRY }
            val fcsEvent = data.events.find { it.event == RoastEvent.FCs }
            val dropEvent = data.events.find { it.event == RoastEvent.DROP }

            val chargeX = chargeEvent?.let { timeToX(it.timeSeconds) } ?: plotLeft
            val dryX = dryEvent?.let { timeToX(it.timeSeconds) }
            val fcsX = fcsEvent?.let { timeToX(it.timeSeconds) }
            val dropX = dropEvent?.let { timeToX(it.timeSeconds) } ?: plotRight

            dryX?.let { dx ->
                drawRect(palette.PhaseDrying, Offset(chargeX, plotTop), Size(dx - chargeX, plotHeight))
            }
            if (dryX != null && fcsX != null) {
                drawRect(palette.PhaseMaillard, Offset(dryX, plotTop), Size(fcsX - dryX, plotHeight))
            } else if (dryX != null) {
                drawRect(palette.PhaseMaillard, Offset(dryX, plotTop), Size(dropX - dryX, plotHeight))
            }
            fcsX?.let { fx ->
                drawRect(palette.PhaseDevelopment, Offset(fx, plotTop), Size(dropX - fx, plotHeight))
            }
        }

        // ===== 2. 网格 =====
        if (vp.showGrid) {
            var t = (vp.timeMin / ChartConfig.TIME_GRID_INTERVAL).toInt() * ChartConfig.TIME_GRID_INTERVAL
            while (t <= vp.timeMax) {
                val x = timeToX(t)
                drawLine(palette.GridLine, Offset(x, plotTop), Offset(x, plotBottom), gridStroke)
                val minutes = (t / 60).toInt()
                val seconds = (t % 60).toInt()
                val textResult = textMeasurer.measure(t.toInt().toTimeLabel(), labelStyle)
                drawText(textResult, topLeft = Offset(x - textResult.size.width / 2f, plotBottom + 4f))
                t += ChartConfig.TIME_GRID_INTERVAL
            }

            var temp = (vp.tempMin / ChartConfig.TEMP_GRID_INTERVAL).toInt() * ChartConfig.TEMP_GRID_INTERVAL
            while (temp <= vp.tempMax) {
                val y = tempToY(temp.toFloat())
                drawLine(palette.GridLine, Offset(plotLeft, y), Offset(plotRight, y), gridStroke)
                val textResult = textMeasurer.measure("${temp.toInt()}°", labelStyle)
                drawText(textResult, topLeft = Offset(plotLeft - textResult.size.width - 4f, y - textResult.size.height / 2f))
                temp += ChartConfig.TEMP_GRID_INTERVAL
            }

            var ror = (vp.rorMin / ChartConfig.ROR_GRID_INTERVAL).toInt() * ChartConfig.ROR_GRID_INTERVAL
            while (ror <= vp.rorMax) {
                val y = rorToY(ror.toFloat())
                drawLine(palette.GridLine.copy(alpha = 0.3f), Offset(plotLeft, y), Offset(plotRight, y), gridStroke)
                val textResult = textMeasurer.measure("${ror.toInt()}", labelStyle)
                drawText(textResult, topLeft = Offset(plotRight + 4f, y - textResult.size.height / 2f))
                ror += ChartConfig.ROR_GRID_INTERVAL
            }
        }

        // ===== 3. 坐标轴边框 =====
        drawRect(palette.GridLine, Offset(plotLeft, plotTop), Size(plotWidth, plotHeight), style = Stroke(gridStroke))

        // ===== 4. 背景曲线 =====
        if (vp.showBackground && data.backgroundCurve.size >= 2) {
            drawCurve(data.backgroundCurve, ::timeToX, ::tempToY, { it.bt }, vp, palette.CurveBackground, bgStroke, dashed = true)
        }

        // ===== 模板锚点标记：空心圈 + 名称，骑在目标曲线上 =====
        if (vp.showBackground && data.backgroundAnchors.isNotEmpty()) {
            val ancStyle = TextStyle(fontSize = 9.sp, color = palette.OnSurface)
            data.backgroundAnchors.sortedBy { it.timeSeconds }.forEach { a ->
                if (a.timeSeconds < vp.timeMin || a.timeSeconds > vp.timeMax) return@forEach
                if (a.bt < vp.tempMin || a.bt > vp.tempMax) return@forEach
                val cx = timeToX(a.timeSeconds)
                val cy = tempToY(a.bt)
                drawCircle(palette.OnSurface, radius = markerW * 1.6f, center = Offset(cx, cy),
                           style = Stroke(width = markerW * 1.2f))
                val mm = (a.timeSeconds / 60).toInt()
                val ss = (a.timeSeconds % 60).toInt()
                val label = buildString {
                    if (a.label.isNotBlank()) append(a.label + " ")
                    append("$mm:${ss.toString().padStart(2, '0')} ${a.bt.toInt()}°")
                }
                val tr = textMeasurer.measure(label, ancStyle)
                drawText(tr, topLeft = Offset(cx - tr.size.width / 2f, cy - tr.size.height - 10f))
            }
        }

        // ===== 5. 实时曲线 =====
        if (data.liveCurve.size >= 2) {
            data.liveCurve.firstOrNull()?.et?.let {
                drawCurve(data.liveCurve, ::timeToX, ::tempToY, { p -> p.et ?: Float.NaN }, vp, palette.CurveET, curveStroke)
            }
            drawCurve(data.liveCurve, ::timeToX, ::tempToY, { it.bt }, vp, palette.CurveBT, curveStroke)
        }

        // ===== 6. RoR 曲线 =====
        if (vp.showRoR && data.liveCurve.size >= 2) {
            drawCurve(data.liveCurve, ::timeToX, ::rorToY, { it.ror }, vp, palette.CurveRoR, rorStroke)
        }

        // ===== 7. 事件标记（标签跟随曲线圆点，自动避让不重叠）=====
        val cnNames = mapOf(
            RoastEvent.CHARGE to "入豆", RoastEvent.DRY to "黄点", RoastEvent.FCs to "一爆",
            RoastEvent.FCe to "一爆止", RoastEvent.SCs to "二爆", RoastEvent.SCe to "二爆止", RoastEvent.DROP to "出豆",
        )
        fun fmtT(sec: Float): String {
            val s = sec.toInt().coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }
        val evtStyle = labelStyle.copy(fontSize = 9.sp, color = palette.OnSurface)
        val placedBoxes = mutableListOf<FloatArray>()   // [left, top, right, bottom]
        fun hits(l: Float, t: Float, r: Float, b: Float): Boolean =
            placedBoxes.any { l < it[2] && r > it[0] && t < it[3] && b > it[1] }

        data.events.sortedBy { it.timeSeconds }.forEach { event ->
            val x = timeToX(event.timeSeconds)
            if (x !in plotLeft..plotRight) return@forEach

            drawLine(palette.OnSurface.copy(alpha = 0.5f), Offset(x, plotTop), Offset(x, plotTop + markerH), markerW)
            val pt = data.liveCurve.minByOrNull { kotlin.math.abs(it.timeSeconds - event.timeSeconds) }
            var dotY: Float? = pt?.let { p ->
                if (p.bt >= vp.tempMin && p.bt <= vp.tempMax) {
                    val dy = tempToY(p.bt)
                    drawCircle(palette.OnSurface, radius = dotR, center = Offset(x, dy))
                    dy
                } else null
            }
            if (dotY == null) return@forEach
            dotY as Float

            val title = "${cnNames[event.event] ?: event.event.name} ${fmtT(event.timeSeconds)}"
            val sub = if (event.temperature > 0f) "${event.temperature.toInt()}°C" else ""
            val t1 = textMeasurer.measure(title, evtStyle)
            val t2 = if (sub.isNotEmpty()) textMeasurer.measure(sub, evtStyle.copy(color = palette.OnSurface.copy(alpha = 0.7f))) else null
            val w = maxOf(t1.size.width, t2?.size?.width ?: 0).toFloat()
            val h = t1.size.height + (t2?.size?.height ?: 0) + 2f

            // 首选：圆点右上；顶部放不下改右下；右侧出界换左侧；再碰撞则向下逐步避让
            var lx = if (x + w + 10f <= plotRight) x + 8f else x - w - 8f
            var ly = if (dotY - h - 8f >= plotTop) dotY - h - 8f else dotY + 10f
            var tries = 0
            while (hits(lx, ly, lx + w, ly + h) && tries < 14) {
                ly += h * 0.55f
                if (ly + h > plotBottom) { lx = if (lx > x) x - w - 8f else x + 8f; ly = dotY - h - 8f }
                tries++
            }

            drawText(t1, topLeft = Offset(lx, ly))
            if (t2 != null) drawText(t2, topLeft = Offset(lx, ly + t1.size.height + 1f))
            placedBoxes.add(floatArrayOf(lx - 2f, ly - 2f, lx + w + 2f, ly + h + 2f))
        }

        // ===== 8. 当前读数 =====
        val lastPoint = data.liveCurve.lastOrNull()
        if (lastPoint != null) {
            val btStyle = boldLabelStyle.copy(color = palette.CurveBT)
            val etStyle = boldLabelStyle.copy(color = palette.CurveET)
            val rorStyle = boldLabelStyle.copy(color = palette.CurveRoR)

            drawText(textMeasurer.measure("BT ${lastPoint.bt.toInt()}°", btStyle), topLeft = Offset(plotLeft + 8f, plotTop + 4f))
            lastPoint.et?.let {
                drawText(textMeasurer.measure("ET ${it.toInt()}°", etStyle), topLeft = Offset(plotLeft + 8f, plotTop + 22f))
            }
            drawText(textMeasurer.measure("RoR ${lastPoint.ror.toFixed1()}°/min", rorStyle), topLeft = Offset(plotLeft + 8f, plotTop + 40f))
        }
    }
}

/**
 * 绘制一条曲线
 */
private fun DrawScope.drawCurve(
    points: List<CurvePoint>,
    timeToX: (Float) -> Float,
    valueToY: (Float) -> Float,
    valueExtractor: (CurvePoint) -> Float,
    vp: ChartViewport,
    color: Color,
    strokeWidth: Float,
    dashed: Boolean = false,
) {
    val path = Path()
    var first = true

    for (point in points) {
        if (point.timeSeconds < vp.timeMin || point.timeSeconds > vp.timeMax) {
            if (!first) continue
            if (point.timeSeconds < vp.timeMin) continue
        }
        val x = timeToX(point.timeSeconds)
        val y = valueToY(valueExtractor(point))
        val v = valueExtractor(point)
        if (v.isNaN()) { first = true; continue }   // null 数据断线
        if (first) { path.moveTo(x, y); first = false }
        else { path.lineTo(x, y) }
    }

    if (dashed) {
        drawPath(path, color, style = Stroke(strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 5f * density))))
    } else {
        drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}