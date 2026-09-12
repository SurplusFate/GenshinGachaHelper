package com.genshin.gachahelper.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop

/**
 * 液态玻璃背板（Backdrop）上下文 —— 全 App 玻璃材质的唯一来源。
 *
 * 玻璃只有在"采样到真实存在的下层像素"时才成立：1.7.27 那套玻璃是手搓的
 * "半透明底 + 描边"，本质是画了一层假底；现在改为 Kyant0/AndroidLiquidGlass 的
 * Backdrop：把 App 根部铺成可被采样的图层，玻璃元素用 [com.kyant.backdrop.drawBackdrop]
 * 对下层像素做真实的高斯模糊 + 圆角 SDF 折射 + 棱边高光。
 *
 * 分层说明（为什么是两层）：
 *  - 背景画布层（canvas）：只含 App 底图。页面内的卡片、顶栏都位于"内容层"内部，
 *    若让它们采样自己所在的图层会形成"边录制边采样"的自引用（拖影/反馈），
 *    因此页面内玻璃统一采样背景画布层。
 *  - 内容层（content）：包含滚动内容。悬浮 DOCK 位于该图层之外，
 *    可以安全地采样它，因此 DOCK 能真实反射滚过它下方的页面内容。
 *
 * 未提供背板时（预览、局部场景），[GlassSurface] / [RoundedBottomDock] 自动退化为
 * 静态材质实现，保证不崩、不白屏。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
