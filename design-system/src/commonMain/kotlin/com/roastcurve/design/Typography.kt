package com.roastcurve.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 烤豆 排版系统
 */
object RoastCurveTypography {
    val Default = Typography(
        // 大标题
        displayLarge = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            lineHeight = 44.sp,
        ),
        displayMedium = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            lineHeight = 36.sp,
        ),
        // 页面标题
        headlineLarge = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
        ),
        headlineMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 28.sp,
        ),
        // 正文
        bodyLarge = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        // 标签
        labelLarge = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        labelSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        ),
        // LCD 温度显示（等宽数字）
        titleLarge = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 48.sp,
            lineHeight = 56.sp,
        ),
        titleMedium = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
        ),
    )
}