package com.genshin.gachahelper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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

    if (useDark) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = WishDark.primary,
                onPrimary = WishDark.bgBottom,
                primaryContainer = WishDark.primaryDim,
                onPrimaryContainer = WishDark.primarySoft,
                secondary = WishDark.accentGold,
                onSecondary = WishDark.bgBottom,
                secondaryContainer = WishDark.accentGoldDim,
                onSecondaryContainer = WishDark.accentGoldSoft,
                tertiary = WishDark.success,
                onTertiary = WishDark.bgBottom,
                background = WishDark.bgBottom,
                onBackground = WishDark.textHigh,
                surface = WishDark.bgCard,
                onSurface = WishDark.textHigh,
                surfaceVariant = WishDark.bgElev,
                onSurfaceVariant = WishDark.textMid,
                surfaceContainer = WishDark.bgCard,
                surfaceContainerHigh = WishDark.bgFloat,
                surfaceContainerHighest = WishDark.bgElev,
                outline = WishDark.border,
                outlineVariant = WishDark.divider,
                error = WishDark.error,
                onError = WishDark.bgBottom,
            ),
            shapes = Shapes(
                extraSmall = WishShapes.xs,
                medium = WishShapes.md,
                large = WishShapes.lg,
                extraLarge = WishShapes.lg,
            ),
            content = content
        )
    } else {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = WishLight.primary,
                onPrimary = WishLight.bgCard,
                primaryContainer = WishLight.primaryDim,
                onPrimaryContainer = WishLight.primarySoft,
                secondary = WishLight.accentGold,
                onSecondary = WishLight.bgCard,
                secondaryContainer = WishLight.accentGoldDim,
                onSecondaryContainer = WishLight.accentGoldSoft,
                tertiary = WishLight.success,
                onTertiary = WishLight.bgCard,
                background = WishLight.bgBottom,
                onBackground = WishLight.textHigh,
                surface = WishLight.bgCard,
                onSurface = WishLight.textHigh,
                surfaceVariant = WishLight.bgElev,
                onSurfaceVariant = WishLight.textMid,
                surfaceContainer = WishLight.bgCard,
                surfaceContainerHigh = WishLight.bgFloat,
                surfaceContainerHighest = WishLight.bgElev,
                outline = WishLight.border,
                outlineVariant = WishLight.divider,
                error = WishLight.error,
                onError = WishLight.bgCard,
            ),
            shapes = Shapes(
                extraSmall = WishShapes.xs,
                medium = WishShapes.md,
                large = WishShapes.lg,
                extraLarge = WishShapes.lg,
            ),
            content = content
        )
    }
}
