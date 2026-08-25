package com.genshin.gachahelper.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Hero 卡片：累计抽数 + 五星数 / 平均出金 / UP率
        item { HeroCard(uiState) }

        // 2. 保底进度网格（2 列）
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
                // 单数池补位，保持两列对齐
                if (rowPools.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // 3. 最近五星横滑
        if (uiState.recentFiveStars.isNotEmpty()) {
            item { SectionHeader("最近五星") }
            item {
                RecentFiveStarsRow(
                    records = uiState.recentFiveStars,
                    intervals = uiState.recentFiveStarIntervals
                )
            }
        }

        // 4. 同步按钮（登录态）/ 登录提示（未登录但有数据）
        item { SyncSection(uiState, viewModel, navController) }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ============================ Hero 卡片 ============================

@Composable
private fun HeroCard(uiState: HomeUiState) {
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 账号信息行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.isLoggedIn) (uiState.nickname ?: "旅行者")
                    else "本地数据",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "UID: ${uiState.uid ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 大数字：累计抽数
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
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
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 三个指标
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeroStat(
                    label = "五星数",
                    value = "$totalFiveStars",
                    valueColor = FiveStarColor
                )
                HeroStat(
                    label = "平均出金",
                    value = if (totalFiveStars > 0)
                        "${String.format("%.1f", avgPulls)}" else "—",
                    valueSuffix = "抽"
                )
                HeroStat(
                    label = "UP率",
                    value = if (charFiveStars > 0)
                        "${String.format("%.0f", upRate)}" else "—",
                    valueSuffix = "%"
                )
            }
        }
    }
}

@Composable
private fun HeroStat(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    valueSuffix: String = ""
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
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
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor
                )
            }
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
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val pityPercent = (poolStats.currentPity.toFloat() / poolStats.pityCeiling).coerceIn(0f, 1f)
    val pityLeft = poolStats.pityCeiling - poolStats.currentPity
    val progressColor = pityProgressColor(poolStats.currentPity)

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${poolStats.currentPity}/${poolStats.pityCeiling}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { pityPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = progressColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "距保底 ${pityLeft} 抽",
                style = MaterialTheme.typography.bodySmall,
                color = if (poolStats.currentPity >= 60) progressColor
                else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
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
    Card(
        modifier = Modifier.width(140.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                text = "${interval}抽",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FourStarColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = record.time.substringBefore(" ").ifEmpty { record.time },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
                        modifier = Modifier.fillMaxWidth()
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
        // 未登录但有本地数据：登录提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "本地数据",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "UID: ${uiState.uid ?: "未知"}（手动导入）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { navController.navigate(Screen.Auth.route) },
                    modifier = Modifier.fillMaxWidth()
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
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}
