package com.roastcurve.app.chart

import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastPhase

/**
 * 烘焙曲线图表的渲染状态
 */
data class ChartViewport(
    val timeMin: Float = 0f,           // X 轴最小时间（秒）
    val timeMax: Float = 600f,         // X 轴最大时间（秒），默认 10 分钟
    val tempMin: Float = 50f,          // 左 Y 轴最小温度
    val tempMax: Float = 250f,         // 左 Y 轴最大温度
    val rorMin: Float = -10f,          // 右 Y 轴最小 RoR
    val rorMax: Float = 30f,           // 右 Y 轴最大 RoR
    val autoScroll: Boolean = true,    // 是否自动滚动到最新数据
    val showBackground: Boolean = true, // 是否显示背景曲线
    val showRoR: Boolean = true,       // 是否显示 RoR 曲线
    val showPhases: Boolean = true,    // 是否显示阶段着色
    val showGrid: Boolean = true,      // 是否显示网格
    val smoothing: Float = 0.3f,       // RoR 平滑系数
)

/**
 * 图表的完整数据状态
 */
data class ChartData(
    val liveCurve: List<CurvePoint> = emptyList(),
    val backgroundCurve: List<CurvePoint> = emptyList(),
    val backgroundAnchors: List<com.roastcurve.shared.model.AnchorPoint> = emptyList(),
    val events: List<EventMarker> = emptyList(),
    val currentPhase: RoastPhase = RoastPhase.PREHEAT,
    val elapsedSeconds: Float = 0f,
    val isRunning: Boolean = false,
)

/**
 * 图表配置常量
 */
object ChartConfig {
    /** 默认时间窗口：15 分钟 */
    const val DEFAULT_TIME_WINDOW = 900f

    /** 默认温度范围 */
    const val DEFAULT_TEMP_MIN = 50f
    const val DEFAULT_TEMP_MAX = 250f

    /** 默认 RoR 范围 */
    const val DEFAULT_ROR_MIN = -15f
    const val DEFAULT_ROR_MAX = 35f

    /** 网格线间隔 */
    const val TIME_GRID_INTERVAL = 60f     // 每分钟
    const val TEMP_GRID_INTERVAL = 25f     // 每 25°C
    const val ROR_GRID_INTERVAL = 5f       // 每 5°C/min

    /** 阶段阈值（可配置） */
    const val DRY_END_TEMP = 150f
    const val FC_START_TEMP = 195f

    /** 曲线线宽 */
    // 描边宽度单位为 dp（RoastChart 内部按屏幕密度换算像素）
    const val CURVE_STROKE_WIDTH = 3f
    const val BACKGROUND_STROKE_WIDTH = 1.75f
    const val ROR_STROKE_WIDTH = 2.25f
    const val GRID_STROKE_WIDTH = 1f
    const val EVENT_MARKER_WIDTH = 2f
    const val PHASE_BORDER_WIDTH = 0.5f

    /** 事件标记长度 */
    const val EVENT_MARKER_HEIGHT = 12f
}