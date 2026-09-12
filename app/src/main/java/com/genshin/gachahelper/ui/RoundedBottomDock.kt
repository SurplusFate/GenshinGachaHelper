package com.genshin.gachahelper.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genshin.gachahelper.ui.navigation.bottomNavItems
import com.genshin.gachahelper.ui.theme.GlassTokens
import com.genshin.gachahelper.ui.theme.WishDark
import com.genshin.gachahelper.ui.theme.WishLight
import com.genshin.gachahelper.ui.theme.isWishDark
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃（Liquid Glass）底部 DOCK。
 *
 * 形态仍对齐参考图：悬浮胶囊、左右留边、不贴底。
 *
 * 1.8.0 起材质换成真玻璃（Kyant0/AndroidLiquidGlass Backdrop）：
 * 1. 胶囊对**下方真实页面内容**做高斯模糊采样 —— 列表滚过 DOCK 时，胶囊里的
 *    内容是实时流动的，这是"液态"的来源；
 * 2. 圆角 SDF 折射：棱边处的内容被玻璃厚度折弯（Android 13+），转动/滚动时
 *    棱边会有真实的透镜错位；
 * 3. 棱边高光由库按形状绘制，比手画描边更接近真实反光；
 * 4. 投影仍然很轻，玻璃不该有厚重黑影；
 * 5. 选中项是胶囊内部的一块更亮的玻璃底片 + 顶部高光，图标文字保持高对比。
 *
 * @param backdrop 由宿主（GachaAppNavHost）注入的内容层背板；为空时退化为静态玻璃
 */
@Composable
fun RoundedBottomDock(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    val dark = isWishDark()

    val capsuleShape = RoundedCornerShape(28.dp)
    val blockShape = RoundedCornerShape(16.dp)

    // 玻璃主体：上亮下暗的半透明白，透出下层内容。
    // 取值统一来自 GlassTokens —— DOCK 与卡片、顶栏共用同一种材料
    val glassTop = if (dark) GlassTokens.fillTopDark else GlassTokens.fillTopLight
    val glassBottom = if (dark) GlassTokens.fillBottomDark else GlassTokens.fillBottomLight
    // 边缘高光：左上最亮，右下衰减到几乎透明（仅在无背板的降级实现里使用）
    val edgeBright = if (dark) GlassTokens.edgeBrightDark else GlassTokens.edgeBrightLight
    val edgeFade = if (dark) GlassTokens.edgeFadeDark else GlassTokens.edgeFadeLight
    // 阴影：极轻，避免玻璃看起来像块塑料
    val shadowColor = if (dark) GlassTokens.shadowDark else GlassTokens.shadowLight

    // 选中玻璃片：以品牌金轻微着色，保持 App 的色调识别
    val blockColor by animateColorAsState(
        targetValue = if (dark) WishDark.accentGold.copy(alpha = 0.22f)
        else WishLight.accentGold.copy(alpha = 0.18f),
        animationSpec = tween(200),
        label = "dockBlockColor"
    )
    val blockEdge = if (dark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.95f)
    val selectedContent by animateColorAsState(
        targetValue = if (dark) WishDark.accentGold else WishLight.accentGoldDim,
        animationSpec = tween(160),
        label = "dockSelectedContent"
    )
    val unselectedContent = if (dark) WishDark.textMid else WishLight.textMid

    val capsuleHeight = 62.dp
    val blockHeight = 46.dp
    val blockSideInset = 6.dp

    val baseModifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(bottom = 10.dp)
        .height(capsuleHeight)

    val glassFill = Brush.verticalGradient(listOf(glassTop, glassBottom))
    val shapeProvider = remember(capsuleShape) { { capsuleShape } }

    val glassModifier = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = shapeProvider,
            effects = {
                blur(GlassTokens.floatBlur.toPx())
                lens(
                    refractionHeight = GlassTokens.lensHeight.toPx(),
                    refractionAmount = GlassTokens.lensAmount.toPx(),
                    depthEffect = true
                )
            },
            highlight = {
                Highlight(
                    width = GlassTokens.edgeWidth,
                    blurRadius = GlassTokens.edgeWidth,
                    alpha = if (dark) 0.55f else 0.85f
                )
            },
            shadow = {
                Shadow(
                    radius = GlassTokens.floatElevation,
                    offset = DpOffset(0.dp, GlassTokens.floatElevation / 3),
                    color = shadowColor
                )
            },
            onDrawSurface = { drawRect(brush = glassFill) }
        )
    } else {
        // 降级：静态玻璃（无背板时兜底）
        val shadowModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Modifier.shadow(
                elevation = GlassTokens.floatElevation,
                shape = capsuleShape,
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
        } else {
            Modifier.shadow(8.dp, capsuleShape, clip = false)
        }
        Modifier
            .then(shadowModifier)
            .clip(capsuleShape)
            .background(glassFill)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(edgeBright, edgeFade, edgeBright.copy(alpha = edgeBright.alpha * 0.55f))
                ),
                shape = capsuleShape
            )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = baseModifier.then(glassModifier)
        ) {
            val itemWidth = maxWidth / bottomNavItems.size
            val blockWidth = itemWidth - blockSideInset * 2
            val blockOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex + blockSideInset,
                animationSpec = tween(
                    durationMillis = 220,
                    easing = FastOutLinearInEasing
                ),
                label = "dockBlockOffset"
            )

            // 下层：选中玻璃片（小圆角矩形，仅覆盖当前 tab 的图标 + 文字）
            Box(
                modifier = Modifier
                    .offset(x = blockOffset, y = (capsuleHeight - blockHeight) / 2)
                    .width(blockWidth)
                    .height(blockHeight)
                    .clip(blockShape)
                    .background(blockColor)
                    // 玻璃片顶部高光，模拟玻璃棱边
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(blockEdge, Color.Transparent)
                        ),
                        shape = blockShape
                    )
            )

            // 上层：页签内容（图标 + 文字）
            Row(modifier = Modifier.fillMaxSize()) {
                bottomNavItems.forEachIndexed { index, screen ->
                    val selected = index == selectedIndex

                    val contentColor by animateColorAsState(
                        targetValue = if (selected) selectedContent else unselectedContent,
                        animationSpec = tween(140),
                        label = "dockIconColor$index"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                tint = contentColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = screen.title,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = contentColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * DOCK 悬浮于内容之上，各页滚动列表需在底部预留的空白高度：
 * 胶囊 62dp + 下沿空隙 10dp + 12dp 视觉余量，另加系统导航栏高度。
 * 列表滚到底时最后一项不会被胶囊压住。
 */
@Composable
fun dockContentBottomPadding(): Dp =
    84.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
