package com.genshin.gachahelper.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃令牌：全 App 唯一的玻璃材质取值来源。
 *
 * 所有玻璃容器（底部 DOCK / 内容卡片 / 顶栏 / 芯片 / 弹窗）一律从这里取色，
 * 严禁再在页面里散落写 `copy(alpha = ...)` —— 这是"全 App 风格统一"的根基。
 *
 * 取值体系与底部 DOCK（1.7.26 定稿）完全一致，保证同一块玻璃出现在任何位置
 * 都是同一种材料：
 *  - fill   竖向渐变填充，上亮下暗，让玻璃有"厚度"而不是一块死白；
 *  - edge   线性渐变描边，左上最亮、右下衰减，模拟玻璃棱边的折射高光；
 *  - shadow 极轻投影，只负责把玻璃"托起来"，重了就像塑料。
 */
object GlassTokens {

    // ---------- 填充：悬浮层（DOCK 等独立浮块） ----------
    val fillTopDark = Color.White.copy(alpha = 0.16f)
    val fillBottomDark = Color.White.copy(alpha = 0.05f)
    val fillTopLight = Color.White.copy(alpha = 0.62f)
    val fillBottomLight = Color.White.copy(alpha = 0.34f)

    // ---------- 填充：内容卡片（面积大、数量多，取值更收敛，避免满屏发亮糊成一片） ----------
    // 1.8.0：接真实背板模糊后，填充必须更透，否则模糊被白底盖住等于没接
    val cardFillTopDark = Color.White.copy(alpha = 0.09f)
    val cardFillBottomDark = Color.White.copy(alpha = 0.03f)
    val cardFillTopLight = Color.White.copy(alpha = 0.62f)
    val cardFillBottomLight = Color.White.copy(alpha = 0.42f)

    // ---------- 填充：顶栏（横向长条，上亮下暗，底部收到最暗以压住滚动内容） ----------
    val barFillTopDark = Color.White.copy(alpha = 0.12f)
    val barFillBottomDark = Color.White.copy(alpha = 0.04f)
    val barFillTopLight = Color.White.copy(alpha = 0.72f)
    val barFillBottomLight = Color.White.copy(alpha = 0.46f)

    // ---------- 边缘高光：悬浮层 ----------
    val edgeBrightDark = Color.White.copy(alpha = 0.34f)
    val edgeFadeDark = Color.White.copy(alpha = 0.06f)
    val edgeBrightLight = Color.White.copy(alpha = 0.90f)
    val edgeFadeLight = Color.White.copy(alpha = 0.22f)

    // ---------- 边缘高光：内容卡片（一屏十几张卡，描边必须淡，否则到处发光） ----------
    val cardEdgeDark = Color.White.copy(alpha = 0.18f)
    val cardEdgeLight = Color.White.copy(alpha = 1.00f)

    // ---------- 投影 ----------
    val shadowDark = Color.Black.copy(alpha = 0.45f)
    val shadowLight = Color.Black.copy(alpha = 0.14f)
    val cardShadowDark = Color.Black.copy(alpha = 0.30f)
    val cardShadowLight = Color.Black.copy(alpha = 0.08f)

    // ---------- 档位 ----------
    /** 玻璃描边统一 1dp，全 App 不得出现第二种描边宽度 */
    val edgeWidth: Dp = 1.dp
    /** 悬浮块抬升高度（DOCK） */
    val floatElevation: Dp = 8.dp
    /** 卡片抬升高度，比悬浮块低一档，保持层级差 */
    val cardElevation: Dp = 6.dp
    /** 顶栏贴边，不抬升 */
    val barElevation: Dp = 0.dp

    // ---------- 液态玻璃（Backdrop）参数 ----------
    /** 卡片背板模糊半径：一屏多张卡，取值收敛，中端机不能掉帧 */
    val cardBlur: Dp = 6.dp
    /** 顶栏背板模糊半径 */
    val barBlur: Dp = 10.dp
    /** DOCK 背板模糊半径：全屏只有一块，可以更"厚" */
    val floatBlur: Dp = 14.dp
    /** 折射带高度：棱边多少厚度内的光线被弯折（Android 13+ 生效） */
    val lensHeight: Dp = 12.dp
    /** 折射强度：越大越"厚"，过大会让棱边处的内容明显错位 */
    val lensAmount: Dp = 18.dp
}
