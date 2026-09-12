package com.genshin.gachahelper.ui.theme

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun isWishDark(): Boolean = MaterialTheme.colorScheme.surface == WishDark.bgCard

@Composable
fun wishAccentGold(): Color = if (isWishDark()) WishDark.accentGold else WishLight.accentGold

@Composable
fun wishSuccess(): Color = if (isWishDark()) WishDark.success else WishLight.success

@Composable
fun wishWarning(): Color = if (isWishDark()) WishDark.warning else WishLight.warning

@Composable
fun wishDivider(): Color = if (isWishDark()) WishDark.divider else WishLight.divider

@Composable
fun wishTextHigh(): Color = if (isWishDark()) WishDark.textHigh else WishLight.textHigh

@Composable
fun wishTextMid(): Color = if (isWishDark()) WishDark.textMid else WishLight.textMid

@Composable
fun wishOnPrimaryFill(): Color = if (isWishDark()) WishDark.textHigh else WishLight.bgCard

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}

@Composable
fun Modifier.wishSkyBackground(dark: Boolean = isWishDark()): Modifier {
    val top = if (dark) SkyDarkTop else SkyLightTop
    val bottom = if (dark) WishDark.bgBottom else WishLight.bgBottom
    return this.background(Brush.verticalGradient(listOf(top, bottom)))
}

/**
 * 首页夜空鎏金背景：跟随主题——深色模式保留深空渐变；
 * 浅色模式使用冷调浅蓝白渐变（与外壳 WishLight 系同色相），
 * 避免暖黄底与浅色外壳互相冲突造成"三截色"割裂感。
 */
@Composable
fun Modifier.homeNightBackdrop(dark: Boolean = isWishDark()): Modifier {
    return if (dark) {
        this.background(
            Brush.verticalGradient(
                0f to Color(0xFF0A1128),
                0.42f to Color(0xFF0D1531),
                1f to Color(0xFF060A1C)
            )
        )
    } else {
        this.background(
            Brush.verticalGradient(
                0f to Color(0xFFF8FAFF),
                0.5f to Color(0xFFF2F5FD),
                1f to Color(0xFFE8ECF9)
            )
        )
    }
}

/**
 * 首页玻璃卡片底色：深色模式为半透明白玻璃（透出深空背景）；
 * 浅色模式为纯白卡，与整个 App 的浅色外壳统一 —— 首页不再出现整片深蓝大卡。
 */
@Composable
fun wishCardBg(): Color {
    return if (isWishDark()) {
        Color.White.copy(alpha = 0.05f)
    } else {
        Color(0xFFFFFFFF)
    }
}

/** 首页英雄卡玻璃质感渐变：深色为半透明深蓝 + 顶部鎏金光晕；浅色为白→极浅蓝白渐变 */
@Composable
fun Modifier.homeHeroCardGradient(): Modifier {
    val colors = if (isWishDark()) {
        listOf(
            Color(0xFF1A2448).copy(alpha = 0.94f),
            Color(0xFF101836).copy(alpha = 0.96f)
        )
    } else {
        listOf(
            Color(0xFFFFFFFF),
            Color(0xFFEFF2FC)
        )
    }
    return this.background(Brush.linearGradient(colors))
}

@Composable
fun Modifier.wishCardGradient(
    start: Color = if (isWishDark()) WishDark.bgFloat else WishLight.bgFloat,
    end: Color = if (isWishDark()) WishDark.bgCard else WishLight.bgCard
): Modifier = this.background(Brush.linearGradient(listOf(start, end)))

fun Modifier.goldGlowBorder(
    glowColor: Color = WishDark.accentGold,
    radius: Dp = 18.dp,
    strokeWidth: Dp = 1.5.dp,
    shape: androidx.compose.foundation.shape.RoundedCornerShape = WishShapes.md
): Modifier = this
    .drawBehind {
        drawRoundRect(
            color = glowColor.copy(alpha = 0.08f),
            style = Stroke(width = (strokeWidth * 3).toPx()),
            cornerRadius = CornerRadius(radius.toPx() * 1.2f)
        )
    }
    .border(strokeWidth, glowColor.copy(alpha = 0.85f), shape)

fun rarityColor(rarity: Int): Color = when (rarity) {
    5 -> FiveStarColor
    4 -> FourStarColor
    else -> ThreeStarColor
}

@Composable
fun WishEmptyGlow(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .background(
                    brush = Brush.radialGradient(
                        listOf(FiveStarColor.copy(alpha = 0.06f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )
        content()
    }
}
