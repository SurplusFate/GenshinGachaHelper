package com.genshin.gachahelper.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.genshin.gachahelper.ui.theme.GlassTokens
import com.genshin.gachahelper.ui.theme.WishShapes
import com.genshin.gachahelper.ui.theme.isWishDark
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 全 App 唯一的玻璃材质容器（液态玻璃 / Liquid Glass）。
 *
 * 页面里所有"卡片 / 面板 / 浮层"都必须用它表达，禁止再手搓
 * `background(...) + border(...) + shadow(...)` —— 材质统一靠的是"
 * 只有一处实现"，而不是"每页都照着抄一遍"。
 *
 * 1.8.0 起质感来自 Kyant0/AndroidLiquidGlass 的 Backdrop：
 *  - 真实采样下层像素（背景画布），做高斯模糊；
 *  - 圆角 SDF 折射：棱边附近的光线被"玻璃厚度"折弯，形成真实厚度感（Android 13+）；
 *  - 棱边高光由库统一绘制（0.5~1dp 的柔和亮边），不再是硬描边。
 *
 * 降级：API < 31 无 RenderEffect，模糊/折射自动失效；背板缺失时退化为静态材质。
 * 两种情况都仍是一块合格的玻璃，不会崩、不会白屏。
 *
 * @param shape    圆角，请从 [WishShapes] 取值
 * @param elevation 抬升高度，默认 [GlassTokens.cardElevation]
 * @param borderStroke 非空时用业务语义描边（如稀有度色）替代默认玻璃棱边
 * @param onClick  非空时整块可点击（带无涟漪的玻璃质感反馈）
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = WishShapes.md,
    elevation: Dp = GlassTokens.cardElevation,
    borderStroke: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = isWishDark()
    val backdrop = LocalGlassBackdrop.current

    val fill = Brush.verticalGradient(
        listOf(
            if (dark) GlassTokens.cardFillTopDark else GlassTokens.cardFillTopLight,
            if (dark) GlassTokens.cardFillBottomDark else GlassTokens.cardFillBottomLight
        )
    )
    val edgeBright = if (dark) GlassTokens.cardEdgeDark else GlassTokens.cardEdgeLight
    val edge = Brush.linearGradient(
        listOf(edgeBright, Color.Transparent, edgeBright.copy(alpha = edgeBright.alpha * 0.5f))
    )
    val shadowColor = if (dark) GlassTokens.cardShadowDark else GlassTokens.cardShadowLight
    val shapeProvider = remember(shape) { { shape } }

    val box = if (backdrop != null) {
        // 液态玻璃：真实采样 + 模糊 + 圆角折射 + 库自带棱边高光/投影
        var glass = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = shapeProvider,
            effects = {
                blur(GlassTokens.cardBlur.toPx())
                // lens 折射只支持圆角类 Shape（CornerBasedShape），传 RectangleShape 等
                // 其它形状会抛 UnsupportedOperationException 直接崩页，这里做形状防御。
                if (shape is CornerBasedShape) {
                    lens(
                        refractionHeight = GlassTokens.lensHeight.toPx(),
                        refractionAmount = GlassTokens.lensAmount.toPx(),
                        depthEffect = true
                    )
                }
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
                    radius = elevation,
                    offset = DpOffset(0.dp, elevation / 3),
                    color = shadowColor
                )
            },
            // 玻璃自身的着色：仍是那层极淡的白，负责把下层模糊压出"磨砂"质感
            onDrawSurface = { drawRect(brush = fill) }
        )
        if (borderStroke != null) {
            // 业务语义描边（稀有度色）叠在玻璃高光之上
            glass = glass.border(borderStroke, shape)
        }
        glass
    } else {
        // 降级：静态玻璃（1.7.27 的实现，无背板时兜底）
        val shadowModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Modifier.shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
        } else {
            Modifier.shadow(elevation, shape, clip = false)
        }
        var legacy = modifier
            .then(shadowModifier)
            .clip(shape)
            .background(fill)
        legacy = if (borderStroke != null) {
            legacy.border(borderStroke, shape)
        } else {
            legacy.border(GlassTokens.edgeWidth, edge, shape)
        }
        legacy
    }

    val clickable = if (onClick != null) {
        box.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    } else {
        box
    }
    Column(modifier = clickable, content = content)
}

/** [GlassSurface] 的语义别名：用于内容型数据卡，写法更贴近业务语义。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = WishShapes.md,
    elevation: Dp = GlassTokens.cardElevation,
    borderStroke: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) = GlassSurface(
    modifier = modifier,
    shape = shape,
    elevation = elevation,
    borderStroke = borderStroke,
    onClick = onClick,
    content = content
)

/**
 * 玻璃顶栏：与 DOCK、卡片同一种材料。
 *
 * 顶栏不抬升（[GlassTokens.barElevation]），滚动时内容从玻璃下透出 —— 1.8.0 起
 * 透出的是"真实被模糊过的内容"：顶栏同样采样背景画布层做模糊。
 * 高光只保留底沿一条（横条四边都发亮在视觉上是多余的），因此关闭库的统一棱边，
 * 底沿高光自己画。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val dark = isWishDark()
    val backdrop = LocalGlassBackdrop.current
    val fill = Brush.verticalGradient(
        listOf(
            if (dark) GlassTokens.barFillTopDark else GlassTokens.barFillTopLight,
            if (dark) GlassTokens.barFillBottomDark else GlassTokens.barFillBottomLight
        )
    )
    // 底沿高光：一条从透明到亮白的横线，模拟玻璃下边缘的折射
    val bottomEdge = if (dark) GlassTokens.cardEdgeDark else GlassTokens.cardEdgeLight
    val bottomEdgeBrush = Brush.verticalGradient(
        listOf(Color.Transparent, Color.Transparent, bottomEdge)
    )
    val titleColor = if (dark) {
        com.genshin.gachahelper.ui.theme.WishDark.textHigh
    } else {
        com.genshin.gachahelper.ui.theme.WishLight.textHigh
    }
    val barShape = RoundedCornerShape(0.dp)
    val shapeProvider = remember { { barShape } }

    val glassModifier = if (backdrop != null) {
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = shapeProvider,
                effects = { blur(GlassTokens.barBlur.toPx()) },
                // 顶栏贴边：不要四边高光与投影，只保留底沿高光
                highlight = null,
                shadow = null,
                onDrawSurface = { drawRect(brush = fill) }
            )
            .border(
                width = GlassTokens.edgeWidth,
                brush = bottomEdgeBrush,
                shape = barShape
            )
    } else {
        Modifier
            .background(fill)
            .border(
                width = GlassTokens.edgeWidth,
                brush = bottomEdgeBrush,
                shape = barShape
            )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(glassModifier)
    ) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = titleColor,
                navigationIconContentColor = titleColor,
                actionIconContentColor = titleColor
            )
        )
    }
}

/**
 * 全 App 统一背景画布。
 *
 * 玻璃只有在"有层次的背景"上才成立：若页面是纯色底，半透明玻璃与实心卡
 * 在观感上没有区别，材质语言就散了。此画布与首页夜空同族，
 * 深色为深空渐变、浅色为冷调浅蓝白渐变。
 *
 * 1.8.0 追加两枚大范围柔光斑：真实模糊 + 折射需要"可被弯折的细节"，
 * 纯线性渐变被模糊后与原来没差别，柔光斑能让玻璃的厚度与折射真正显形。
 */
@Composable
fun Modifier.appGlassBackdrop(dark: Boolean = isWishDark()): Modifier {
    val base = if (dark) {
        Brush.verticalGradient(
            0f to Color(0xFF0A1128),
            0.42f to Color(0xFF0D1531),
            1f to Color(0xFF060A1C)
        )
    } else {
        Brush.verticalGradient(
            0f to Color(0xFFF8FAFF),
            0.5f to Color(0xFFF2F5FD),
            1f to Color(0xFFE8ECF9)
        )
    }
    val glowA = if (dark) Color(0x334B6CFF) else Color(0x2E9DB8FF)
    val glowB = if (dark) Color(0x26C9A227) else Color(0x1FC9A227)

    return this.drawBehind {
        drawRect(brush = base)
        val radiusA = size.minDimension * 0.95f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowA, Color.Transparent),
                center = Offset(size.width * 0.16f, size.height * 0.10f),
                radius = radiusA
            ),
            radius = radiusA,
            center = Offset(size.width * 0.16f, size.height * 0.10f)
        )
        val radiusB = size.minDimension * 0.80f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowB, Color.Transparent),
                center = Offset(size.width * 0.94f, size.height * 0.72f),
                radius = radiusB
            ),
            radius = radiusB,
            center = Offset(size.width * 0.94f, size.height * 0.72f)
        )
    }
}
