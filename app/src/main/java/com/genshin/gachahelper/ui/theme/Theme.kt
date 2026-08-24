package com.genshin.gachahelper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 原神风格配色
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A5C7A),
    secondary = Color(0xFFD4AF37),
    tertiary = Color(0xFF8B4513),
    background = Color(0xFFF5F5DC),
    surface = Color(0xFFFFFEF0),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4A90B0),
    secondary = Color(0xFFE8C547),
    tertiary = Color(0xFFCD853F),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
)

// 星级颜色
val FiveStarColor = Color(0xFFFFD700)
val FourStarColor = Color(0xFF87CEEB)
val ThreeStarColor = Color(0xFF90EE90)

/**
 * 应用全局主题。
 *
 * @param themeMode 用户选择的主题模式（随系统/白天/夜间）
 * @param systemDark 系统当前是否处于夜间模式（默认使用 Compose 提供的 isSystemInDarkTheme()）
 */
@Composable
fun GenshinGachaHelperTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
