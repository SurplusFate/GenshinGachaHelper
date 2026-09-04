---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 86c452ae2501760602e02cad594b8743_3a8827fda86e11f188bd525400287e28
    ReservedCode1: XmpXknWnJK9CFZDFgxF6uPNwLsYKCGkMpjLSmizH/EatWLUmHhzM3iYViRbKcFtFQLlWvuzR24iyrj1pTP/GRF2+anqv+q6XP3+a8SRTcvtqIbO7Quuw3cLN7kCADjLAPAJegGHNl30LXUL3Ncq7ALQbMiTd4ZKYu1ELRfQel6ZvHVmOM84nEotA7AU=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 86c452ae2501760602e02cad594b8743_3a8827fda86e11f188bd525400287e28
    ReservedCode2: XmpXknWnJK9CFZDFgxF6uPNwLsYKCGkMpjLSmizH/EatWLUmHhzM3iYViRbKcFtFQLlWvuzR24iyrj1pTP/GRF2+anqv+q6XP3+a8SRTcvtqIbO7Quuw3cLN7kCADjLAPAJegGHNl30LXUL3Ncq7ALQbMiTd4ZKYu1ELRfQel6ZvHVmOM84nEotA7AU=
---

# GenshinGachaHelper UI 改版「祈愿星辉」详细实施规格书（AI 可执行版）

> 目标仓库：https://github.com/SurplusFate/GenshinGachaHelper
> 技术栈：Android 原生 · Kotlin · Jetpack Compose · Material3 · MVVM · Hilt · Room
> 本规格书自包含：设计 token 代码、全局替换规则、逐文件改造任务、验收门禁均在此文内，无需再读其他文档。
> 执行前请 `git pull` 或 clone 最新 main 分支，所有"现状"描述基于当前 main 快照，若代码有变以实际为准。

---

## 0. 给执行 AI 的阅读指引

1. 先通读 §1-§3，理解设计方向与 token。
2. 按 §4 顺序执行：P0 → P1 → P2，每阶段完成后编译 + 冒烟自测再进下一阶段。
3. 所有颜色与圆角一律引用 token，禁止再写 `Color(0x...)` / `RoundedCornerShape(数字.dp)` 字面量（§4.3 列出的清理项除外）。
4. 不改变任何业务逻辑、数据库结构、网络协议与导航结构，只做表现层改造。
5. 本文给出的"现状片段"为定位锚点；若函数已重构，按函数名与视觉意图定位。

---

## 1. 项目上下文

### 1.1 仓库结构（UI 相关）

```
app/src/main/java/com/genshin/gachahelper/
├── MainActivity.kt                    // 入口 Activity
├── ui/
│   ├── GachaAppNavHost.kt             // Scaffold + TopAppBar + NavigationBar + NavHost
│   ├── theme/
│   │   ├── Theme.kt                   // ★ P0 替换（本规格书 §3.2）
│   │   └── ThemeRepository.kt         // ThemeMode 枚举 + DataStore（勿改）
│   ├── auth/AuthScreen.kt             // P2：登录页视觉
│   ├── home/HomeScreen.kt             // P1：首页（HeroLuckCard / LuckRing / PityGridCard / RecentFiveStarsRow / SyncSection）
│   ├── history/HistoryScreen.kt       // P1：历史记录（SearchBar / FilterChips / SummaryBar / RecordItem / EmptyState）
│   ├── report/ReportScreen.kt         // 二级页：报告（保持轻量，仅随主题自动生效）
│   ├── settings/SettingsScreen.kt     // P2：默认主题改 DARK 的开关位置
│   └── stats/StatsScreen.kt           // P1：统计（OverviewContent / TimelineContent / CollectionContent / CalendarContent）
```

### 1.2 导航结构（勿改，仅视觉）

- 底部导航 4 页：首页(Home) / 历史(History) / 统计(Stats) / 设置(Settings)
- 二级页：授权登录(Auth) / 抽卡报告(Report)，均有返回键
- 当前默认进首页；跟随系统亮暗主题

### 1.3 现状问题清单（本次改造目标）

| # | 问题 | 位置 |
|---|---|---|
| A | 米色底 + 青色主色，"记账软件感"，缺原神品牌联想 | Theme.kt |
| B | 圆角 8/10/12/16/24dp 硬编码散落 | HomeScreen / StatsScreen / HistoryScreen / ReportScreen |
| C | 颜色硬编码（#1DC981、#EFAA17、#FFD700 等） | StatsScreen / HomeScreen |
| D | 首页 Hero 卡用 primaryContainer 纯色，无层次 | HomeScreen.kt HeroLuckCard |
| E | 五星出货卡片与普通卡片视觉区分弱（仅 0.15 alpha 边框） | HistoryScreen.kt RecordItem / HomeScreen RecentFiveStarCard |
| F | 时间线/日历 5 星高亮不明显，连接线均用金色（应为弱化线条+金色节点） | StatsScreen.kt TimelineNode / CalendarDayCell |
| G | NavigationBar 默认灰底，无品牌层次 | GachaAppNavHost.kt |
| H | 空状态/登录页纯文字纯色 | 各 EmptyState / AuthScreen |

---

## 2. 视觉方向与设计原则

### 2.1 方向一句话

"祈愿星辉"：**深空蓝紫底 + 流光金强调**，让抽卡记录像游戏内祈愿界面一样有仪式感；金色只属于五星与欧气时刻，日常信息用星蓝表达。

### 2.2 形态策略

- 主形态：深色（DARK）。沉浸、图表清晰、金色对比度高。
- 浅色保留：跟随系统时使用（室外可读性兜底）。
- 默认值：P2 阶段在 Settings 把新用户默认主题设为 DARK；老用户不动（尊重已选）。

### 2.3 设计原则

1. **金色稀缺**：金色只出现在 5 星出货、欧气评分、Hero 环、关键 CTA。禁用金色做正文/大面积背景。
2. **层级靠底色与描边，不靠阴影堆叠**：背景三层 bgBottom→bgCard→bgFloat 递进。
3. **圆角收敛**：xs=10 / md=18 / lg=28 / pill，与 M3 Shapes 槽位映射。
4. **动效克制**：只有两种微动效（出货金光 600ms 单次渐隐、欧气环呼吸 2s 循环），且遵循系统"减弱动态效果"。
5. **无障碍底线**：正文对比度 ≥ WCAG AA（4.5:1），金色不承载小号正文。

---

## 3. 设计 Token（完整代码，自包含）

### 3.1 新增文件 `ui/theme/ColorTokens.kt`

```kotlin
package com.genshin.gachahelper.ui.theme

import androidx.compose.ui.graphics.Color

/** 深色（主形态） */
object WishDark {
    val bgBottom = Color(0xFF0B1020)      // 页面最底层（星空底）
    val bgCard   = Color(0xFF121830)      // 卡片 / surface
    val bgFloat  = Color(0xFF1A2140)      // 弹层 / NavigationBar / 悬浮
    val bgElev   = Color(0xFF232C52)      // 按压态 / 输入框填充
    val primary     = Color(0xFF8FA8FF)
    val primarySoft = Color(0xFFC8D6FF)
    val primaryDim  = Color(0xFF5E72C9)
    val accentGold     = Color(0xFFFFC94D)
    val accentGoldSoft = Color(0xFFFFE3A3)
    val accentGoldDim  = Color(0xFFB8860B)
    val textHigh = Color(0xFFEDF0FF)
    val textMid  = Color(0xFFAAB2D0)
    val textLow  = Color(0xFF6E7696)
    val border   = Color(0xFF2A3359)
    val divider  = Color(0xFF20273F)
    val success  = Color(0xFF34D399)
    val warning  = Color(0xFFFF8A65)
    val error    = Color(0xFFFF6B81)
}

/** 浅色（跟随系统） */
object WishLight {
    val bgBottom = Color(0xFFF2F4FC)
    val bgCard   = Color(0xFFFFFFFF)
    val bgFloat  = Color(0xFFFFFFFF)
    val bgElev   = Color(0xFFE6E9F5)
    val primary     = Color(0xFF3D5AFE)
    val primarySoft = Color(0xFF6C84FF)
    val primaryDim  = Color(0xFF2C3DA8)
    val accentGold     = Color(0xFFD99A06)
    val accentGoldSoft = Color(0xFFFFE9B8)
    val accentGoldDim  = Color(0xFF8A6100)
    val textHigh = Color(0xFF141A33)
    val textMid  = Color(0xFF4C5578)
    val textLow  = Color(0xFF8A92AF)
    val border   = Color(0xFFDCE1F0)
    val divider  = Color(0xFFEDF0F8)
    val success  = Color(0xFF0E9F6E)
    val warning  = Color(0xFFE2542F)
    val error    = Color(0xFFE0245E)
}

/** 游戏内品质色（明暗通用） */
val FiveStarColor  = Color(0xFFFFD700)
val FourStarColor  = Color(0xFF7FB8FF)
val ThreeStarColor = Color(0xFF8FBF9F)
```

> 注意：`FiveStarColor / FourStarColor / ThreeStarColor` 现定义在旧 `Theme.kt` 顶部。新增本文件后，**必须从旧 Theme.kt 删除同名常量**，否则编译冲突（见 §4.1 步骤 3）。

### 3.2 新增文件 `ui/theme/ShapeTokens.kt`

```kotlin
package com.genshin.gachahelper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.Shape
import androidx.compose.ui.unit.dp

object WishShapes {
    val xs: Shape    = RoundedCornerShape(10.dp)  // 列表项 / 输入框 / 标签
    val md: Shape    = RoundedCornerShape(18.dp)  // 常规卡片
    val lg: Shape    = RoundedCornerShape(28.dp)  // Hero / 弹层 / 大图块
    val pill: Shape  = RoundedCornerShape(50)     // 标签 / 进度胶囊
}
```

### 3.3 替换文件 `ui/theme/Theme.kt`（整文件替换）

```kotlin
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
```

### 3.4 通用视觉辅助（新增 `ui/theme/WishVisuals.kt`，供 P1 复用）

```kotlin
package com.genshin.gachahelper.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 星空渐变背景：页面级（bottom 深 -> top 微亮） */
@Composable
fun Modifier.wishSkyBackground(dark: Boolean = true): Modifier {
    val top = if (dark) Color(0xFF131A36) else Color(0xFFE8EDFB)
    val bottom = if (dark) WishDark.bgBottom else WishLight.bgBottom
    return this.background(Brush.verticalGradient(listOf(top, bottom)))
}

/** 卡片渐变容器（替代纯色 primaryContainer 卡片）：左上泛光 -> 右下沉稳 */
@Composable
fun Modifier.wishCardGradient(
    start: Color = WishDark.bgFloat,
    end: Color = WishDark.bgCard
): Modifier =
    this.background(Brush.linearGradient(listOf(start, end)))

/** 金色辉光描边（5星出货 / Hero 环）：多层 drawBehind 模拟 outer glow */
fun Modifier.goldGlowBorder(
    glowColor: Color = WishDark.accentGold,
    radius: Dp = 8.dp,
    strokeWidth: Dp = 1.5.dp,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp)
): Modifier = this
    .drawBehind {
        // 外发光：两层半透明描边叠出辉光
        drawRoundRect(
            color = glowColor.copy(alpha = 0.08f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = (strokeWidth * 3).toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx() * 1.2f)
        )
    }
    .border(strokeWidth, glowColor.copy(alpha = 0.85f), shape)

/** 品质色辅助：根据星级给出描边主色（明暗通用） */
fun rarityColor(rarity: Int): Color = when (rarity) {
    5 -> FiveStarColor
    4 -> FourStarColor
    else -> ThreeStarColor
}
```

---

## 4. 分阶段任务

### 阶段 P0：主题地基（必须最先完成，全部页面立即获得新配色）

#### 4.1 P0 步骤

1. 新增 `ColorTokens.kt`（§3.1 完整内容）。
2. 新增 `ShapeTokens.kt`（§3.2 完整内容）。
3. **替换** `Theme.kt` 为 §3.3 内容。
   - 删除旧 `Theme.kt` 顶部的 `FiveStarColor / FourStarColor / ThreeStarColor` 定义；
   - 保留 `ThemeMode` 枚举在 `ThemeRepository.kt`（该文件不动）；
   - 原 `GenshinGachaHelperTheme(themeMode, systemDark, content)` 签名不变，调用点无需改。
4. 新增 `WishVisuals.kt`（§3.4）。
5. 编译：`./gradlew :app:compileDebugKotlin` 必须通过。
6. 冒烟：深浅两套截图，检查无纯白/纯黑异常、文字可读。

#### 4.2 P0 验收

- [ ] 编译通过，无重复声明冲突
- [ ] 全局底色变深蓝黑（暗色）/ 浅蓝白（浅色），不再是米色
- [ ] Card 默认圆角变为 18dp 级别（通过 M3 shapes 生效）

#### 4.3 P0 全局清理（贯穿 P1/P2）

搜索并逐处替换（先改视觉层文件，遇业务语义色保留并在注释说明）：

| 搜索 | 替换建议 | 说明 |
|---|---|---|
| `RoundedCornerShape(8.dp)` | `WishShapes.xs`（徽章/小标签）或按语境微调 | 图标徽章 8dp 可保留个别，见 §4.3.2 |
| `RoundedCornerShape(10.dp)` | `WishShapes.xs` | 列表项 |
| `RoundedCornerShape(12.dp)` | `WishShapes.md` | 卡片化内容 |
| `RoundedCornerShape(16.dp)` | `WishShapes.md` | 原 Hero 卡等 |
| `RoundedCornerShape(24.dp)` | `WishShapes.lg` | 大图块/弹层 |
| `Color(0xFF1DC981)` | `WishDark.success`（暗）/ `WishLight.success`（浅） | 出现在 StatsScreen / HomeScreen 的幸运绿 |
| `Color(0xFFEFAA17)` | `WishDark.accentGold` | 首页暖色强调 |

**注意**：
- `CardDefaults.cardElevation` 阴影在新深色底上不显眼，可降低 defaultElevation 到 0~1dp，改用边框 `border = BorderStroke(1.dp, colorScheme.outlineVariant)` 表达层级（逐卡处理，见 P1）。
- 星级品质色 `Color(0xFFFFD700) / Color(0xFF87CEEB) / Color(0xFF90EE90)` 一律替换为 `FiveStarColor / FourStarColor / ThreeStarColor`（新值：金 #FFD700、星蓝 #7FB8FF、星辉绿 #8FBF9F）。
- 勿用 IDE 全局替换一次到位，请按文件逐个 review，避免误伤图标色。

---

### 阶段 P1：核心页面表现力（按用户价值排序）

#### P1-1 首页 Hero 卡（HomeScreen.kt `HeroLuckCard`）

**现状锚点**（main 快照）：

```kotlin
Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer
    ),
    shape = RoundedCornerShape(16.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
) {
    Row(...) { /* 左：账号行 + 大数字 + 三栏统计；右：LuckRing */ }
}
```

**目标**：
1. 整卡改为星空渐变（`wishCardGradient`），左上透出星蓝泛光，向右下渐深。
2. 卡片外层叠加 `goldGlowBorder`，圆角用 `WishShapes.lg`（28dp）——它承载"欧气值"这一最情绪化的数据。
3. 大数字（总抽数）改金色渐变文字或金色高亮（仅数字，字号 displaySmall），正文仍用 onSurface。

**做法**：
- 将 `Card` 的 `containerColor` 方案替换为：`Box(Modifier.clip(WishShapes.lg).background(Brush.linearGradient(...)))` 承载内容，或用 `Card(colors=..., border=...)` 时配合 `drawBehind`。推荐结构（示意，变量名与现状保持一致）：

```kotlin
Card(
    modifier = Modifier
        .fillMaxWidth()
        .goldGlowBorder(shape = RoundedCornerShape(28.dp)),
    colors = CardDefaults.cardColors(
        containerColor = Color.Transparent
    ),
    shape = RoundedCornerShape(28.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
) {
    Box(
        modifier = Modifier
            .wishCardGradient(
                start = if (isDark()) WishDark.bgFloat else WishLight.bgFloat,
                end = if (isDark()) WishDark.bgCard else WishLight.bgCard
            )
    ) {
        Row(...) { /* 原内容保留 */ }
    }
}
```

- `isDark()` 可通过 `MaterialTheme.colorScheme.background == WishDark.bgBottom` 判断，或把 `useDark` 提升传入；最省事：在 Hero 内部 `val dark = MaterialTheme.colorScheme.surface == WishDark.bgCard`。
- 金色大数字：`color = WishDark.accentGold`（暗色）/ `WishLight.accentGold`（浅色），加 `FontWeight.Black` 增仪式感。

**验收**：首页顶部卡片呈渐变蓝紫 + 金色辉光描边，右侧 LuckRing 观感协调；浅色下仍有足够对比。

#### P1-2 首页五星卡（HomeScreen.kt `RecentFiveStarRow` / `RecentFiveStarCard` / `LuckDetailCard`）

**目标**：五星星卡与普通内容一眼可分。
1. `RecentFiveStarCard`：卡片边框用 `FiveStarColor.copy(alpha=0.55f)`，右上角或左侧加 5 星徽章；内容正常 onSurface。
2. `LuckDetailRow` 中与运气/欧气相关数值：正数/好运用 `WishDark.success` 绿，负数用 `WishDark.warning`；不要整行变色。
3. 出货微光动效（可选，见 §5.3）：五星卡首次进入屏幕时 600ms 金色 alpha 渐隐一次，用 `Animatable` 实现，勿循环。

#### P1-3 历史记录五星强调（HistoryScreen.kt `RecordItem`）

**现状锚点**（main 快照）：整行 `Surface` 圆角 10dp、边框 `rarityColor.copy(alpha=0.15f)`；五星只多了 itemName 金色 + "距上次 N 抽"小标签。

**目标**：
1. 五星记录行：边框升级为 `FiveStarColor.copy(alpha=0.5f)`，星级徽章底 `FiveStarColor.copy(alpha=0.22f)`；四星 `FourStarColor.copy(alpha=0.35f)`。
2. 五星 itemName 保持金色（已是），四星 itemName 用 onSurface 但可在左侧徽章区分（已是）。
3. 圆角统一：行 `RoundedCornerShape(10.dp)` → `WishShapes.xs`；间隔标签 `RoundedCornerShape(10.dp)` → `WishShapes.pill`。

**验收**：历史页滚动时，五星行有金色呼吸边框感（静态即可），与普通行明显分层。

#### P1-4 统计-五星时间线（StatsScreen.kt `TimelineContent` / `TimelineNode`）

**现状锚点**（main 快照）：节点圆点 `background(FiveStarColor)` + 白描边；连接线全程 `FiveStarColor.copy(alpha=0.5f)`。

**目标**：
1. 连接线改为中性弱色：暗色 `WishDark.divider` / 浅色 `WishLight.divider`（线条只是骨架，金色让给节点）。
2. 五星节点圆点改为金色渐变小球：`Brush.radialGradient(listOf(Color(0xFFFFF3C4), FiveStarColor))` + 外圈辉光（`goldGlowBorder` 或 drawBehind 光晕）。
3. 节点下方"XX抽"文字保持金色；池名用小号 textMid。
4. 其它时间线（若存在四星节点）按品质色区分的逻辑保留，仅弱化非五星。

**验收**：时间线视觉重心回到金色节点，线条不再刺眼。

#### P1-5 统计-日历（StatsScreen.kt `CalendarContent` / `CalendarDayCell` / `CalendarLegend`）

**现状**：日期格子按抽出数着色（count / fiveCount）。
**目标**：
1. 五星期格子：底色 `FiveStarColor.copy(alpha=0.28f)`，数字用 `WishDark.textHigh`（暗色）避免金底金字；四星 `FourStarColor.copy(alpha=0.22f)`。
2. 图例（LegendBox）同步替换为上述新色，保持图例与格子一致。
3. 圆角统一小尺寸 `WishShapes.xs`。

#### P1-6 统计/报告数字风格（StatsScreen `StatMini` / ReportScreen 行）

**目标**：数字类数值可用 `FontFamily.Monospace` 或 `FontWeight.Bold` 统一，令统计对仗整齐；星级大字保持品质色。涉及字体族需确认 app 字体是否含等宽，若效果不佳只加粗即可。

---

### 阶段 P2：全局细节与登录/空态/导航

#### P2-1 底部导航（GachaAppNavHost.kt NavigationBar）

1. `NavigationBar` 容器色：暗色 `WishDark.bgFloat`，浅色 `WishLight.bgFloat`。
2. 选中项 indicator 容器色：`secondaryContainer` 已映射金色 dim（#B8860B 暗色），保证选中即"星火点亮"；图标/文字选中色用 `onSecondaryContainer`（金色软色）。
3. TopAppBar 标题色用 onBackground，默认即可，勿加粗标题以外的装饰。

#### P2-2 登录页与空状态

1. `AuthScreen` 背景加 `wishSkyBackground`（页面级星空渐变）；若页面含 Card 表单，表单容器用 `WishShapes.lg`。
2. 各 `EmptyState`：纯文字 → 图标/插画上方加一层星辉径向渐变底（圆 200dp 金色 alpha 0.06），文案保留。
3. 不要引入图片资源（保持仓库轻量），用 Compose 画圆/Canvas 模拟星光即可。

#### P2-3 默认主题

- `SettingsScreen` 的主题选择若提供"跟随系统/白天/夜间"：保持可选；同时在首次启动默认值由 `FOLLOW_SYSTEM` 改为 `DARK`（改动位置在 ThemeRepository 默认值或首次启动写入处——**注意这是行为变更**，若产品不希望强制暗色，可跳过此项，仅保留用户可选）。

---

## 5. 动效规范

### 5.1 允许的动效

| 动效 | 触发 | 时长 | 规则 |
|---|---|---|---|
| 出货金光 | 五星卡片首次出现在组合中 | 600ms alpha 0→0.25→0 一次 | 每屏只对首张五星播放 |
| 欧气环呼吸 | 首页 LuckRing | 2s 循环 alpha 0.6↔1.0 | 仅 Hero 环 |
| 页面转场 | NavHost 默认 | 保留默认 | 不加自定义 |

### 5.2 无障碍

- `Settings` 或系统"移除动画"开启（`Settings.Global.ANIMATOR_DURATION_SCALE == 0`）时禁用 §5.1 两类动效。
- 所有新增可点击元素保持 48dp 最小触摸目标。
- 新增图形（星辉/辉光）为装饰性，`contentDescription = null` 或纯 Canvas 不读屏。

### 5.3 出货金光参考实现（HomeScreen 内私有 Composable 用）

```kotlin
@Composable
private fun rememberFiveStarGlow(play: Boolean): Float {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(play) {
        if (play) {
            alpha.snapTo(0f)
            alpha.animateTo(0.25f, tween(200))
            alpha.animateTo(0f, tween(400))
        }
    }
    return alpha.value
}
```

用法：五星卡 `Modifier.drawBehind { if (glow > 0f) drawRoundRect(color = FiveStarColor.copy(alpha = glow), ...) }`。

---

## 6. 质量门禁（最终验收清单）

- [ ] `./gradlew :app:compileDebugKotlin` 与 `lintDebug` 通过
- [ ] 全仓库 grep 无新增 `Color(0x...)` 字面量（§4.3 清理项除外）
- [ ] 全仓库 grep 无新增 `RoundedCornerShape(数字.dp)`（徽章等特例已注释）
- [ ] 深浅两套主题手动过一遍：首页 / 历史 / 统计(四个子 Tab) / 设置 / 登录页 / 空态
- [ ] 五星出货行在首页/历史/统计时间线三处视觉一致（同一金色体系）
- [ ] 金色未用于正文小字，正文对比度目测无发灰
- [ ] 原有登录、同步、导入导出、统计计算功能不受影响（重点回归导航与列表点击）
- [ ] 低电量/无障碍"移除动画"下无卡顿无闪烁

## 7. 建议执行顺序总结

```
P0: 新增 ColorTokens / ShapeTokens / WishVisuals → 替换 Theme.kt → 编译
P1: HeroLuckCard 渐变金环 → RecentFiveStar 金色强调 → History RecordItem
    → Stats TimelineNode → Stats CalendarDayCell → 数字风格
P2: NavigationBar → AuthScreen / EmptyState → 默认主题(DARK，可选)
→ 全量验收 §6
```

## 8. 风险与回滚

- 星级色常量迁移：新增 ColorTokens 后忘记从旧 Theme.kt 删除会编译冲突——按 §4.1 步骤 3 执行即可。
- `surfaceContainer` 系列槽位需 M3 1.1+ 支持；若编译报错，将 `surfaceContainer*` 行删除并改用 `surfaceVariant` 承载（功能等价，视觉略降级）。
- 金色对比度：暗色下 #FFC94D 作为图标/描边无问题，勿做 labelSmall 正文。
- 回滚：全部改动集中在 `ui/` 表现层，`git revert` 或手动还原三个 Screen 文件即可，不影响数据层。
*（内容由AI生成，仅供参考）*
