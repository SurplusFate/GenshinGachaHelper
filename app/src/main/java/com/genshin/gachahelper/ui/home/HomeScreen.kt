package com.genshin.gachahelper.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.analysis.LuckConfidence
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.sync.SyncState
import com.genshin.gachahelper.ui.dockContentBottomPadding
import com.genshin.gachahelper.ui.navigation.Screen
import com.genshin.gachahelper.ui.theme.FiveStarColor
import com.genshin.gachahelper.ui.theme.WishEmptyGlow
import com.genshin.gachahelper.ui.theme.WishShapes
import com.genshin.gachahelper.ui.theme.goldGlowBorder
import com.genshin.gachahelper.ui.GlassSurface
import com.genshin.gachahelper.ui.theme.homeHeroCardGradient
import com.genshin.gachahelper.ui.theme.homeNightBackdrop
import com.genshin.gachahelper.ui.theme.isWishDark
import com.genshin.gachahelper.ui.theme.rememberReduceMotion
import com.genshin.gachahelper.ui.theme.wishCardBg
import com.genshin.gachahelper.ui.theme.wishAccentGold
import com.genshin.gachahelper.ui.theme.wishSuccess
import com.genshin.gachahelper.ui.theme.wishWarning

@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // 首页固定夜空背景：深空渐变 + 星点 + 顶部鎏金氛围光
    Box(
        modifier = Modifier
            .fillMaxSize()
            .homeNightBackdrop()
    ) {
        // 浅色模式页面为冷调浅蓝白底，不再叠加星空/光晕，保持干净统一
        if (isWishDark()) {
            NightSkyCanvas(modifier = Modifier.fillMaxSize())
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = wishAccentGold())
                }
            }

            // 既没登录也没有本地数据 → 引导页
            !uiState.isLoggedIn && !uiState.hasData -> {
                WishEmptyGlow(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "原神抽卡助手",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "授权米游社自动同步，或手动导入 UIGF 数据，分析你的抽卡运气",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { navController.navigate(Screen.Auth.route) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = WishShapes.lg,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE7C877),
                                contentColor = Color(0xFF1A1508)
                            )
                        ) {
                            Text("授权米游社", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.Settings.route) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = WishShapes.lg,
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        ) {
                            Text("导入 UIGF 数据")
                        }
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 12.dp,
                    // 底部为悬浮 DOCK 预留空白：最后一张卡片不会被胶囊压住
                    bottom = dockContentBottomPadding() + 12.dp
                )
            ) {
                // 1. Hero + 运气环 (方案A + 方案C 融合)
                item { HeroLuckCard(uiState) }

                // 2. 最近出金横滑 (方案B)
                if (uiState.recentFiveStars.isNotEmpty()) {
                    item { SectionHeader("最近出金") }
                    item {
                        RecentFiveStarsRow(
                            records = uiState.recentFiveStars,
                            intervals = uiState.recentFiveStarIntervals
                        )
                    }
                }

                // 3. 保底进度网格 (方案A)
                item { SectionHeader("保底进度") }
                val pools = listOf(
                    "角色池" to uiState.characterStats,
                    "角色池-2" to uiState.character2Stats,
                    "武器池" to uiState.weaponStats,
                    "常驻池" to uiState.standardStats,
                    "新手池" to uiState.noviceStats,
                    "集录池" to uiState.chronicledStats
                )
                items(pools.chunked(2)) { rowPools ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowPools.forEach { (label, stats) ->
                            PityGridCard(
                                label = label,
                                poolStats = stats,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowPools.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 4. 运气拆解 (方案C)
                item { SectionHeader("运气拆解") }
                item { LuckDetailCard(uiState) }

                // 5. 同步按钮
                item { SyncSection(uiState, viewModel, navController) }
            }
        }
    }
}

/** 首页夜空氛围层：顶部鎏金光晕 + 随机星点（仅深色模式显示星点，浅色暖白底不画星） */
@Composable
private fun NightSkyCanvas(modifier: Modifier = Modifier) {
    val dark = isWishDark()
    val stars = remember {
        // 固定种子生成 42 颗星，避免重组随机闪烁
        val seed = 20260908
        var state = seed
        fun next(): Float {
            state = state * 1103515245 + 12345
            return ((state ushr 16) and 0x7fff) / 32767f
        }
        List(42) {
            Triple(next() * 1f, next() * 1f, 0.15f + next() * 0.7f)
        }
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 左上 / 右上两团鎏金氛围光（模仿网页版 radial-gradient）
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFE7C877).copy(alpha = 0.10f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(w * 0.92f, 0f),
                radius = w * 0.9f
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFF4C6FFF).copy(alpha = 0.06f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(0f, h * 0.2f),
                radius = w * 0.75f
            )
        )
        if (dark) {
            stars.forEach { (fx, fy, alpha) ->
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.85f),
                    radius = if (alpha > 0.72f) 1.6f else 1.1f,
                    center = androidx.compose.ui.geometry.Offset(fx * w, fy * h)
                )
            }
        }
    }
}

// ============================ Hero + 运气环 ============================

@Composable
private fun HeroLuckCard(uiState: HomeUiState) {
    // 角色池301和400各自显示单池统计，汇总时两个池都计入
    val stats = listOfNotNull(
        uiState.characterStats,
        uiState.character2Stats,
        uiState.weaponStats,
        uiState.standardStats,
        uiState.noviceStats,
        uiState.chronicledStats
    )
    val totalPulls = stats.sumOf { it.totalPulls }
    val totalFiveStars = stats.sumOf { it.fiveStarCount }
    // 使用计算引擎生成的全局报告平均值，禁止在 UI 层用 totalPulls / totalFiveStars
    val avgPulls = uiState.report?.avgPullsPerFiveStar ?: 0.0

    // UP 率：仅角色池（301 + 400）统计
    val upFiveStars = (uiState.characterStats?.upFiveStarCount ?: 0) +
            (uiState.character2Stats?.upFiveStarCount ?: 0)
    val charFiveStars = (uiState.characterStats?.fiveStarCount ?: 0) +
            (uiState.character2Stats?.fiveStarCount ?: 0)
    val upRate = if (charFiveStars > 0) upFiveStars.toDouble() / charFiveStars * 100 else 0.0

    // 运气评分：使用计算引擎基于真实概率模型生成的综合运气分
    val luckScore = uiState.report?.overallLuckScore ?: 0
    val luckConfidence = uiState.report?.overallLuckConfidence ?: LuckConfidence.INSUFFICIENT
    val luckVerdict = luckVerdictText(luckScore)

    val gold = wishAccentGold()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .goldGlowBorder(
                glowColor = gold,
                radius = 28.dp,
                shape = WishShapes.lg
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        shape = WishShapes.lg,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .homeHeroCardGradient()
                .drawBehind {
                    // 顶部鎏金氛围光（模仿网页版 hero 右上角 radial-gradient）
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFFE7C877).copy(alpha = 0.13f), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(size.width * 0.92f, size.height * 0.06f),
                            radius = size.width * 0.85f
                        )
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 账号信息行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.isLoggedIn) (uiState.nickname ?: "旅行者")
                        else "本地数据",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = homeTextHigh()
                    )
                    Text(
                        text = "UID: ${uiState.uid ?: "未绑定"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = homeTextMid()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 大数字
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$totalPulls",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = gold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "抽",
                        style = MaterialTheme.typography.titleMedium,
                        color = homeTextMid(),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = "五星 $totalFiveStars · 平均 ${String.format("%.1f", avgPulls)} 抽出金",
                    style = MaterialTheme.typography.bodySmall,
                    color = homeTextMid()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 三栏统计
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    HeroStat(
                        label = "五星数",
                        value = "$totalFiveStars",
                        valueColor = FiveStarColor,
                        modifier = Modifier.weight(1f)
                    )
                    HeroStat(
                        label = "平均出金",
                        value = if (totalFiveStars > 0)
                            String.format("%.1f", avgPulls) else "—",
                        valueSuffix = if (totalFiveStars > 0) "抽" else "",
                        modifier = Modifier.weight(1f)
                    )
                    HeroStat(
                        label = "UP率",
                        value = if (charFiveStars > 0)
                            String.format("%.0f", upRate) else "—",
                        valueSuffix = if (charFiveStars > 0) "%" else "",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 运气指数：整行轻量条，彻底避开右侧窄栏的挤压与截断
                if (totalFiveStars > 0) {
                    LuckScoreStrip(
                        score = luckScore,
                        confidenceName = luckConfidence.displayName,
                        verdict = luckVerdict
                    )
                } else {
                    // 空态：没有五星时不渲染 0 分，避免误导为"0 分非酋本酋"
                    Text(
                        text = "暂无运气分析 · 出金后生成",
                        style = MaterialTheme.typography.labelSmall,
                        color = homeTextLow()
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    valueSuffix: String = ""
) {
    val effectiveValueColor = valueColor ?: homeTextHigh()
    Column(
        modifier = modifier
            .clip(WishShapes.xs)
            .background(homeChip())
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = homeTextMid()
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = effectiveValueColor
            )
            if (valueSuffix.isNotEmpty()) {
                Text(
                    text = valueSuffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = effectiveValueColor,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

/** 运气指数轻量条：整行「标签 + 细进度条 + 分数」，下方一行结论，不再占据侧栏 */
@Composable
private fun LuckScoreStrip(
    score: Int,
    confidenceName: String,
    verdict: String,
    modifier: Modifier = Modifier
) {
    val animatedScore = remember { Animatable(0f) }
    LaunchedEffect(score) {
        animatedScore.snapTo(0f)
        animatedScore.animateTo(score.toFloat(), tween(1200, easing = FastOutSlowInEasing))
    }

    val gold = wishAccentGold()
    val fraction = (animatedScore.value / 100f).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "运气指数",
                style = MaterialTheme.typography.labelSmall,
                color = homeTextMid()
            )
            Spacer(modifier = Modifier.width(10.dp))
            // 细进度条：轨道 + 金色渐变填充，长度随分值生长；无扫光、无往复动画
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(homeTrack())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(gold.copy(alpha = 0.55f), gold)
                            )
                        )
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${animatedScore.value.toInt()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = gold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$verdict · $confidenceName",
            style = MaterialTheme.typography.labelSmall,
            color = homeTextLow()
        )
    }
}



// ============================ 保底进度网格 ============================

@Composable
private fun PityGridCard(
    label: String,
    poolStats: PoolStats?,
    modifier: Modifier = Modifier
) {
    if (poolStats == null) {
        GlassSurface(
            modifier = modifier,
            shape = WishShapes.md
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = homeTextLow()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = homeTextWeak()
                )
            }
        }
        return
    }

    val isNovice = poolStats.poolType == GachaType.NOVICE.value
    // 新手池：pityCeiling=20 是「池总抽数」，不是五星保底阈值，进度语义为"已抽 X/20"
    // 其他池：进度语义"已垫抽 X/保底上限"
    val pityPercent = (poolStats.currentPity.toFloat() / poolStats.pityCeiling).coerceIn(0f, 1f)
    val pityLeft = poolStats.pityCeiling - poolStats.currentPity
    val progressColor = if (isNovice) {
        noviceProgressColor(poolStats.currentPity, poolStats.pityCeiling)
    } else {
        pityProgressColor(poolStats.currentPity, poolStats.pityCeiling)
    }

    GlassSurface(
        modifier = modifier,
        shape = WishShapes.md
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = homeTextMid()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "${poolStats.currentPity}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = progressColor
                )
                Text(
                    text = "/${poolStats.pityCeiling}",
                    style = MaterialTheme.typography.labelSmall,
                    color = homeTextLow()
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // 胶囊形进度条：使用 BoxWithConstraints 显式指定宽度，避免 Surface 内部
            // 约束传播导致 fillMaxWidth(fraction) 失效（进度条永远显示 100%）
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(WishShapes.pill)
                    .background(homeTrack())
            ) {
                Box(
                    modifier = Modifier
                        .width(maxWidth * pityPercent)
                        .height(6.dp)
                        .clip(WishShapes.pill)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    progressColor.copy(alpha = 0.55f),
                                    progressColor,
                                    progressColor.copy(alpha = 0.9f)
                                )
                            )
                        )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isNovice) {
                    // 新手池只有 20 抽：抽满自动关闭，没有五星保底概念
                    if (pityLeft <= 0) "新手池已关闭" else "池剩余 ${pityLeft} 抽（共 ${poolStats.pityCeiling} 抽）"
                } else {
                    "距保底 ${pityLeft} 抽"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isNovice) {
                    when {
                        pityLeft <= 0 -> homeTextHigh()
                        poolStats.currentPity >= 15 -> if (isWishDark()) Color(0xFF8AB4FF) else Color(0xFF1E4FD8)
                        else -> homeTextHigh()
                    }
                } else {
                    if (poolStats.currentPity >= 60) progressColor
                    else homeTextHigh()
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 本池累计统计（用户在首页即可看到各池的"总抽数 / 五星数"，不用切到统计页）
            val fiveStarColor = FiveStarColor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "总 ${poolStats.totalPulls} 抽",
                    style = MaterialTheme.typography.labelSmall,
                    color = homeTextMid()
                )
                Text(
                    text = "五星 ${poolStats.fiveStarCount}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = fiveStarColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun pityProgressColor(currentPity: Int, ceiling: Int): Color {
    val remaining = ceiling - currentPity
    return when {
        remaining <= 10 -> wishAccentGold()
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * 新手池进度颜色：远距离用星蓝，接近用完用金色。
 */
@Composable
private fun noviceProgressColor(currentPity: Int, ceiling: Int): Color {
    val remaining = if (ceiling <= 0) 0 else ceiling - currentPity
    return when {
        remaining <= 4 -> wishAccentGold()
        else -> MaterialTheme.colorScheme.primary
    }
}

// ============================ 最近五星横滑 ============================

@Composable
private fun RecentFiveStarsRow(
    records: List<GachaRecordEntity>,
    intervals: List<Int>
) {
    val pairs = records.zip(intervals)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(pairs) { index, (record, interval) ->
            RecentFiveStarCard(
                record = record,
                interval = interval,
                playGlow = index == 0
            )
        }
    }
}

@Composable
private fun RecentFiveStarCard(
    record: GachaRecordEntity,
    interval: Int,
    playGlow: Boolean = false
) {
    val glow = rememberFiveStarGlow(play = playGlow)
    GlassSurface(
        modifier = Modifier
            .width(130.dp)
            .drawBehind {
                if (glow > 0f) {
                    drawRoundRect(
                        color = FiveStarColor.copy(alpha = glow),
                        cornerRadius = CornerRadius(18.dp.toPx())
                    )
                }
            },
        shape = WishShapes.md,
        borderStroke = BorderStroke(1.5.dp, FiveStarColor.copy(alpha = 0.4f))
    ) {
        Box {
            Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 28.dp, bottom = 12.dp)) {
                Text(
                    text = record.itemName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = homeTextHigh(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "距上次 $interval 抽",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = FiveStarColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.time.substringBefore(" ").ifEmpty { record.time },
                    style = MaterialTheme.typography.labelSmall,
                    color = homeTextLow()
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                shape = WishShapes.xs,
                color = FiveStarColor.copy(alpha = 0.22f)
            ) {
                Text(
                    text = "5",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = FiveStarColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ============================ 运气拆解 ============================

@Composable
private fun LuckDetailCard(uiState: HomeUiState) {
    // 角色池301和400各自显示单池统计，汇总时两个池都计入
    val stats = listOfNotNull(
        uiState.characterStats,
        uiState.character2Stats,
        uiState.weaponStats,
        uiState.standardStats,
        uiState.noviceStats,
        uiState.chronicledStats
    )
    val totalPulls = stats.sumOf { it.totalPulls }
    val totalFiveStars = stats.sumOf { it.fiveStarCount }
    // 使用计算引擎生成的全局报告数据，禁止在 UI 层自行计算
    val avgPulls = uiState.report?.avgPullsPerFiveStar ?: 0.0
    val worstLuck = uiState.report?.worstLuck ?: 0
    val bestLuck = uiState.report?.bestLuck ?: 0
    val recentInterval = uiState.recentFiveStarIntervals.firstOrNull() ?: 0

    // UP率：仅角色池（301 + 400）统计
    val upFiveStars = (uiState.characterStats?.upFiveStarCount ?: 0) +
            (uiState.character2Stats?.upFiveStarCount ?: 0)
    val charFiveStars = (uiState.characterStats?.fiveStarCount ?: 0) +
            (uiState.character2Stats?.fiveStarCount ?: 0)
    val upRate = if (charFiveStars > 0) upFiveStars.toDouble() / charFiveStars * 100 else 0.0

    val success = wishSuccess()
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = WishShapes.md
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            LuckDetailRow("平均出金", if (totalFiveStars > 0) String.format("%.1f 抽", avgPulls) else "—", wishAccentGold())
            LuckDetailRow("最近五星", if (recentInterval > 0) "$recentInterval 抽" else "—", success)
            LuckDetailRow("最非", if (worstLuck > 0) "$worstLuck 抽" else "—", wishWarning())
            LuckDetailRow("最欧", if (bestLuck > 0) "$bestLuck 抽" else "—", success)
            LuckDetailRow("UP成功率", String.format("%.0f%%", upRate), MaterialTheme.colorScheme.primary)
            LuckDetailRow("总抽数", "$totalPulls", homeTextHigh())
        }
    }
}

@Composable
private fun LuckDetailRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = homeTextLow()
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

// ============================ 同步 / 登录 ============================

@Composable
private fun SyncSection(
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    navController: NavController
) {
    if (uiState.isLoggedIn) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (val sync = uiState.syncState) {
                is SyncState.Idle, is SyncState.Success -> {
                    if (sync is SyncState.Success) {
                        Text(
                            text = "上次同步：新增 ${sync.totalNew} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Button(
                        onClick = { viewModel.sync() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = WishShapes.lg,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE7C877),
                            contentColor = Color(0xFF1A1508)
                        )
                    ) {
                        Text("同步抽卡记录", fontWeight = FontWeight.Bold)
                    }
                }
                is SyncState.Loading -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = sync.message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is SyncState.Progress -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "正在同步：${sync.currentPool}（${sync.totalRecords} 条）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "新增 ${sync.newRecords} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is SyncState.Error -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = sync.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedButton(
                            onClick = { viewModel.sync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重试")
                        }
                    }
                }
                is SyncState.RateLimited -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "访问过于频繁，请 ${sync.cooldownSeconds} 秒后重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedButton(
                            onClick = { viewModel.sync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("我知道了")
                        }
                    }
                }
            }
        }
    } else {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = WishShapes.md
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "本地数据",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = homeTextHigh()
                    )
                    Text(
                        text = "UID: ${uiState.uid ?: "未绑定"}（手动导入）",
                        style = MaterialTheme.typography.bodySmall,
                        color = homeTextLow()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { navController.navigate(Screen.Auth.route) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = WishShapes.lg,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE7C877),
                            contentColor = Color(0xFF1A1508)
                        )
                    ) {
                    Text("登录米游社以同步最新数据", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ============================ 小工具 ============================

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(WishShapes.pill)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            wishAccentGold().copy(alpha = 0.95f),
                            wishAccentGold().copy(alpha = 0.45f)
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================ 运气评语 ============================

@Composable
private fun rememberFiveStarGlow(play: Boolean): Float {
    val reduceMotion = rememberReduceMotion()
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(play, reduceMotion) {
        if (play && !reduceMotion) {
            alpha.snapTo(0f)
            alpha.animateTo(0.25f, tween(200))
            alpha.animateTo(0f, tween(400))
        }
    }
    return alpha.value
}

/**
 * 运气分文案。
 *
 * 语义：运气分 = P(理论概率模型下出金抽数 > 你的抽数) × 100，
 * 即"模型下比你更非的比例"——对比对象是官方概率生成的理想分布，
 * 不是全服/真实玩家数据库。文案因此使用"模型预期"措辞，
 * 避免用户误读为"与全服玩家比"。
 */
private fun luckVerdictText(score: Int): String = when {
    score >= 90 -> "远超模型预期"
    score >= 70 -> "优于模型预期"
    score >= 45 -> "与模型预期相近"
    score >= 25 -> "低于模型预期"
    else -> "远低于模型预期"
}


// ============================ 首页卡片双主题 helpers ============================
// 深色模式：卡片为深色夜空玻璃，文字白系鎏金；
// 浅色模式：卡片为白/近白，文字自动切到深蓝黑 —— 杜绝"白字浮白卡"与整片深蓝大卡。
@Composable
private fun homeTextHigh(): Color =
    if (isWishDark()) Color.White else Color(0xFF17203A)

@Composable
private fun homeTextMid(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.62f) else Color(0xFF17203A).copy(alpha = 0.68f)

@Composable
private fun homeTextLow(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.45f) else Color(0xFF17203A).copy(alpha = 0.55f)

@Composable
private fun homeTextWeak(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.30f) else Color(0xFF17203A).copy(alpha = 0.36f)

@Composable
private fun homeTrack(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.14f) else Color(0xFF17203A).copy(alpha = 0.12f)

@Composable
private fun homeChip(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.07f) else Color(0xFF17203A).copy(alpha = 0.05f)

@Composable
private fun homeCardBorder(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.08f) else Color(0xFF17203A).copy(alpha = 0.10f)

