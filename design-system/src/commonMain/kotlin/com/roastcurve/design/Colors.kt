package com.roastcurve.design

import androidx.compose.ui.graphics.Color

/**
 * 烤豆 (RoastCurve) 设计系统 — 暖米色/咖啡色主题
 * 与 CoffeeBeanTracker 保持一致的视觉风格
 */

/**
 * 图表/组件共用的调色板接口
 */
interface CurvePalette {
    val OnSurface: Color
    val GridLine: Color
    val GridText: Color
    val CurveBT: Color
    val CurveET: Color
    val CurveRoR: Color
    val CurveBackground: Color
    val PhaseDrying: Color
    val PhaseMaillard: Color
    val PhaseDevelopment: Color
    // 阶段强调色：用于统计卡文字/图例圆点（比色带更深/亮，保证可读）
    val PhaseDryingAccent: Color
    val PhaseMaillardAccent: Color
    val PhaseDevelopmentAccent: Color
}

// ===== 暖米色 Light 主题 =====

object WarmBeige : CurvePalette {
    // 背景色
    val Background = Color(0xFFFAF5EB)       // 暖米白
    val Surface = Color(0xFFFFFBF5)          // 卡片白
    val SurfaceVariant = Color(0xFFF2EBDE)   // 浅米色

    // 主色调
    val Primary = Color(0xFF8B5E3C)          // 咖啡棕
    val PrimaryLight = Color(0xFFB8845A)     // 浅咖啡
    val PrimaryDark = Color(0xFF5C3A1E)      // 深咖啡

    // 强调色
    val Accent = Color(0xFFD4843A)            // 暖橙（用于关键数据、按钮）
    val AccentLight = Color(0xFFF0B87A)      // 浅橙

    // 文字
    val OnBackground = Color(0xFF3D2B1F)     // 深棕文字
    override val OnSurface = Color(0xFF4A3728)        // 中棕文字
    val OnSurfaceVariant = Color(0xFF7A6857) // 浅棕辅助文字

    // 状态色
    val Success = Color(0xFF5B8C5A)          // 绿（正常）
    val Warning = Color(0xFFD4843A)          // 橙（警告）
    val Error = Color(0xFFBF4B3A)            // 红（错误/超温）

    // 曲线色
    override val CurveBT = Color(0xFFD4843A)           // 豆温曲线 — 暖橙
    override val CurveET = Color(0xFF8B5E3C)           // 炉温曲线 — 咖啡棕
    override val CurveRoR = Color(0xFF1F7A8C)          // RoR 曲线 — 深青（与暖橙豆温线拉开对比）
    override val CurveBackground = Color(0xFFD4C5B2)   // 背景曲线 — 浅米灰

    // 阶段色带（Artisan 惯例：脱水绿 / 美拉徳黄 / 发展棕，色相拉开易辨识）
    override val PhaseDrying = Color(0xFFD9EAD3)       // 干燥期 — 软绿
    override val PhaseMaillard = Color(0xFFFAEFC0)     // 梅纳期 — 麦黄
    override val PhaseDevelopment = Color(0xFFEBD9BC)  // 发展期 — 焦糖浅棕
    // 阶段强调色（文字可读版）
    override val PhaseDryingAccent = Color(0xFF55804B)
    override val PhaseMaillardAccent = Color(0xFF9C7514)
    override val PhaseDevelopmentAccent = Color(0xFF96652F)

    // 网格
    override val GridLine = Color(0xFFE8DDD0)          // 网格线
    override val GridText = Color(0xFFA89885)          // 坐标轴文字
}

// ===== 深色主题 =====

object DarkRoast : CurvePalette {
    val Background = Color(0xFF1A1410)        // 深黑棕
    val Surface = Color(0xFF241D16)           // 卡片深棕
    val SurfaceVariant = Color(0xFF2E251C)    // 浅深棕

    val Primary = Color(0xFFD4A574)           // 暖金棕
    val PrimaryLight = Color(0xFFE8C9A8)      // 浅金棕
    val PrimaryDark = Color(0xFFA67C52)       // 深金棕

    val Accent = Color(0xFFF0A050)            // 暖橙
    val AccentLight = Color(0xFFF5C898)       // 浅橙

    val OnBackground = Color(0xFFE8DDD0)      // 浅米文字
    override val OnSurface = Color(0xFFD4C5B2)         // 中米文字
    val OnSurfaceVariant = Color(0xFFA89885)  // 灰米辅助文字

    val Success = Color(0xFF7CB87B)
    val Warning = Color(0xFFF0A050)
    val Error = Color(0xFFE05545)

    override val CurveBT = Color(0xFFF0A050)
    override val CurveET = Color(0xFFD4A574)
    override val CurveRoR = Color(0xFF52BFD4)
    override val CurveBackground = Color(0xFF4A3D30)

    override val PhaseDrying = Color(0xFF243122)
    override val PhaseMaillard = Color(0xFF352D14)
    override val PhaseDevelopment = Color(0xFF3A2818)
    override val PhaseDryingAccent = Color(0xFF93C08A)
    override val PhaseMaillardAccent = Color(0xFFE0C368)
    override val PhaseDevelopmentAccent = Color(0xFFD4A574)

    override val GridLine = Color(0xFF3A2E24)
    override val GridText = Color(0xFF7A6857)
}