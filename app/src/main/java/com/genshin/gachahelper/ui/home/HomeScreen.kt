package com.genshin.gachahelper.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.sync.SyncState
import com.genshin.gachahelper.ui.navigation.Screen
import com.genshin.gachahelper.ui.theme.FiveStarColor
import com.genshin.gachahelper.ui.theme.FourStarColor

@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // 既没登录也没有本地数据 → 引导页
    if (!uiState.isLoggedIn && !uiState.hasData) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "原神抽卡助手",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "授权米游社自动同步，或手动导入 UIGF 数据，分析你的抽卡运气",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { navController.navigate(Screen.Auth.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("授权米游社")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { navController.navigate(Screen.Settings.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导入 UIGF 数据")
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 12.dp, bottom = 24.dp
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

// ============================ Hero + 运气环 ============================

@Composable
private fun HeroLuckCard(uiState: HomeUiState) {
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
    val avgPulls = if (totalFiveStars > 0) totalPulls.toDouble() / totalFiveStars else 0.0

    // UP 率：仅角色池（301 + 400）统计
    val upFiveStars = (uiState.characterStats?.upFiveStarCount ?: 0) +
            (uiState.character2Stats?.upFiveStarCount ?: 0)
    val charFiveStars = (uiState.characterStats?.fiveStarCount ?: 0) +
            (uiState.character2Stats?.fiveStarCount ?: 0)
    val upRate = if (charFiveStars > 0) upFiveStars.toDouble() / charFiveStars * 100 else 0.0

    // 运气评分
    val luckScore = calculateLuckScore(avgPulls, totalFiveStars)
    val luckVerdict = luckVerdictText(luckScore)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧：大数字 + 三栏统计
            Column(modifier = Modifier.weight(1f)) {
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
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "UID: ${uiState.uid ?: "未知"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 大数字
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$totalPulls",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "抽",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = "五星 $totalFiveStars · 平均 ${String.format("%.1f", avgPulls)} 抽出金",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
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
            }

            // 右侧：运气环
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LuckRing(score = luckScore)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = luckVerdict,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "高于 $luckScore% 旅行者",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun HeroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    valueSuffix: String = ""
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            if (valueSuffix.isNotEmpty()) {
                Text(
                    text = valueSuffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = valueColor,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun LuckRing(score: Int) {
    val animatedScore by animateFloatAsState(targetValue = score.toFloat(), label = "luck")
    val primaryColor = MaterialTheme.colorScheme.onPrimaryContainer
    val trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)

    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            // 背景圆
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke)
            )
            // 进度弧
            val sweep = 360f * animatedScore / 100f
            drawArc(
                color = primaryColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "LUCK",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }
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
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
        pityProgressColor(poolStats.currentPity)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // 胶囊形进度条
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pityPercent)
                        .clip(RoundedCornerShape(3.dp))
                        .background(progressColor)
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
                        pityLeft <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        poolStats.currentPity >= 15 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                } else {
                    if (poolStats.currentPity >= 60) progressColor
                    else MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun pityProgressColor(currentPity: Int): Color = when {
    currentPity >= 75 -> MaterialTheme.colorScheme.error
    currentPity >= 60 -> Color(0xFFEFAA17)
    else -> FiveStarColor
}

/**
 * 新手池进度颜色：按已用 20 抽池总量的比例变色
 * - ≤10 抽：刚开池，正常色
 * - 11~15 抽：提醒色
 * - ≥16 抽：接近用完（20 抽后自动关闭）
 */
@Composable
private fun noviceProgressColor(currentPity: Int, ceiling: Int): Color = when {
    ceiling <= 0 -> FiveStarColor
    currentPity >= ceiling - 4 -> MaterialTheme.colorScheme.primary
    currentPity >= ceiling - 10 -> Color(0xFFEFAA17)
    else -> FiveStarColor
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
        items(pairs) { (record, interval) ->
            RecentFiveStarCard(record = record, interval = interval)
        }
    }
}

@Composable
private fun RecentFiveStarCard(
    record: GachaRecordEntity,
    interval: Int
) {
    Surface(
        modifier = Modifier.width(130.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, FiveStarColor),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = record.itemName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = FiveStarColor,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================ 运气拆解 ============================

@Composable
private fun LuckDetailCard(uiState: HomeUiState) {
    val stats = listOfNotNull(
        uiState.characterStats,
        uiState.character2Stats,
        uiState.weaponStats,
        uiState.standardStats,
        uiState.noviceStats,
        uiState.chronicledStats
    )
    // 只取有五星记录的池来算最非/最欧，避免空池的 0 值污染结果
    val statsWithFiveStars = stats.filter { it.fiveStarCount > 0 }
    val totalPulls = stats.sumOf { it.totalPulls }
    val totalFiveStars = stats.sumOf { it.fiveStarCount }
    val avgPulls = if (totalFiveStars > 0) totalPulls.toDouble() / totalFiveStars else 0.0
    val worstLuck = statsWithFiveStars.maxOfOrNull { it.maxPullsForFiveStar } ?: 0
    val bestLuck = statsWithFiveStars.minOfOrNull { it.minPullsForFiveStar } ?: 0
    val recentInterval = uiState.recentFiveStarIntervals.firstOrNull() ?: 0

    val upFiveStars = (uiState.characterStats?.upFiveStarCount ?: 0) +
            (uiState.character2Stats?.upFiveStarCount ?: 0)
    val charFiveStars = (uiState.characterStats?.fiveStarCount ?: 0) +
            (uiState.character2Stats?.fiveStarCount ?: 0)
    val upRate = if (charFiveStars > 0) upFiveStars.toDouble() / charFiveStars * 100 else 0.0

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            LuckDetailRow("平均出金", if (totalFiveStars > 0) String.format("%.1f 抽", avgPulls) else "—", MaterialTheme.colorScheme.primary)
            LuckDetailRow("最近五星", if (recentInterval > 0) "$recentInterval 抽" else "—", Color(0xFF1DC981))
            LuckDetailRow("最非", if (worstLuck > 0) "$worstLuck 抽" else "—", MaterialTheme.colorScheme.error)
            LuckDetailRow("最欧", if (bestLuck > 0) "$bestLuck 抽" else "—", FiveStarColor)
            LuckDetailRow("UP成功率", String.format("%.0f%%", upRate), MaterialTheme.colorScheme.primary)
            LuckDetailRow("总抽数", "$totalPulls", MaterialTheme.colorScheme.onSurface)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("同步抽卡记录")
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
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "本地数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "UID: ${uiState.uid ?: "未知"}（手动导入）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { navController.navigate(Screen.Auth.route) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("登录米游社以同步最新数据")
                }
            }
        }
    }
}

// ============================ 小工具 ============================

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

// ============================ 运气评分计算 ============================

/**
 * 运气评分：基于平均出金抽数，avg=10→100分, avg=90→0分
 */
private fun calculateLuckScore(avgPulls: Double, totalFiveStars: Int): Int {
    if (totalFiveStars == 0 || avgPulls <= 0) return 0
    return ((90.0 - avgPulls) / 80.0 * 100).coerceIn(0.0, 100.0).toInt()
}

private fun luckVerdictText(score: Int): String = when {
    score >= 80 -> "运气爆棚"
    score >= 65 -> "运气还不错"
    score >= 50 -> "运气一般般"
    score >= 35 -> "运气有点差"
    else -> "是非酋本酋"
}
