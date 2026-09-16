/*
 * RoastCurve（烤豆）—— 咖啡烘焙曲线记录与控制
 * Copyright 2026 MDx
 * https://github.com/MDx-MoJe/RoastCurve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.roastcurve.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 烤豆 (RoastCurve) 主题
 * 暖米色 / 深色双模，与 CoffeeBeanTracker 同一设计语言
 */

private val LightScheme = lightColorScheme(
    primary = WarmBeige.Primary,
    onPrimary = WarmBeige.Surface,
    primaryContainer = WarmBeige.SurfaceVariant,
    onPrimaryContainer = WarmBeige.OnBackground,
    secondary = WarmBeige.Accent,
    onSecondary = WarmBeige.Surface,
    secondaryContainer = WarmBeige.AccentLight,
    background = WarmBeige.Background,
    onBackground = WarmBeige.OnBackground,
    surface = WarmBeige.Surface,
    onSurface = WarmBeige.OnSurface,
    surfaceVariant = WarmBeige.SurfaceVariant,
    onSurfaceVariant = WarmBeige.OnSurfaceVariant,
    error = WarmBeige.Error,
)

private val DarkScheme = darkColorScheme(
    primary = DarkRoast.Primary,
    onPrimary = DarkRoast.Background,
    primaryContainer = DarkRoast.SurfaceVariant,
    onPrimaryContainer = DarkRoast.OnBackground,
    secondary = DarkRoast.Accent,
    onSecondary = DarkRoast.Background,
    secondaryContainer = DarkRoast.AccentLight,
    background = DarkRoast.Background,
    onBackground = DarkRoast.OnBackground,
    surface = DarkRoast.Surface,
    onSurface = DarkRoast.OnSurface,
    surfaceVariant = DarkRoast.SurfaceVariant,
    onSurfaceVariant = DarkRoast.OnSurfaceVariant,
    error = DarkRoast.Error,
)

// 全部按钮胶囊化：形状本身就是「可按压」的视觉语言
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(50),
    small = RoundedCornerShape(50),      // Button/OutlinedButton/TextButton/Chip
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun RoastCurveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = RoastCurveTypography.Default,
        shapes = AppShapes,
        content = content,
    )
}