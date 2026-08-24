package com.genshin.gachahelper.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.ui.theme.FiveStarColor

@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            !uiState.hasData -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "暂无抽卡数据",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "先去首页同步你的抽卡记录吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                val report = uiState.report!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // 总览卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "累计抽卡",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${report.totalPulls} 抽",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatMini(
                                    label = "五星总数",
                                    value = "${report.totalFiveStars}",
                                    valueColor = FiveStarColor
                                )
                                StatMini(
                                    label = "平均出金",
                                    value = "${String.format("%.1f", report.avgPullsPerFiveStar)} 抽"
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 运气数据
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "运气分析",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatMini(
                                    label = "最少抽数出金",
                                    value = "${report.bestLuck} 抽",
                                    valueColor = FiveStarColor
                                )
                                StatMini(
                                    label = "最多抽数出金",
                                    value = "${report.worstLuck} 抽",
                                    valueColor = MaterialTheme.colorScheme.error
                                )
                                StatMini(
                                    label = "UP 成功率",
                                    value = "${String.format("%.0f", report.upSuccessRate * 100)}%"
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 各卡池详情
                    report.characterPoolStats?.let {
                        PoolStatCard(poolLabel = "角色池", stats = it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.character2PoolStats?.let {
                        PoolStatCard(poolLabel = "角色池-2", stats = it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.weaponPoolStats?.let {
                        PoolStatCard(poolLabel = "武器池", stats = it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.standardPoolStats?.let {
                        PoolStatCard(poolLabel = "常驻池", stats = it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.novicePoolStats?.let {
                        PoolStatCard(poolLabel = "新手池", stats = it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.chronicledPoolStats?.let {
                        PoolStatCard(poolLabel = "集录池", stats = it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.stellarPoolStats?.let {
                        PoolStatCard(poolLabel = "千星奇域", stats = it)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 生成报告按钮
                    Button(
                        onClick = { navController.navigate("report") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("生成抽卡报告")
                    }
                }
            }
        }
    }
}

@Composable
fun StatMini(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
fun PoolStatCard(poolLabel: String, stats: PoolStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = poolLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatMini("总抽数", "${stats.totalPulls}")
                StatMini("五星", "${stats.fiveStarCount}", FiveStarColor)
                StatMini("四星", "${stats.fourStarCount}")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatMini("平均出金", "${String.format("%.1f", stats.avgPullsPerFiveStar)} 抽")
                StatMini("当前垫抽", "${stats.currentPity}")
            }
        }
    }
}
