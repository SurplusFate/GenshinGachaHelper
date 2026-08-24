package com.genshin.gachahelper.ui.report

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.genshin.gachahelper.analysis.GachaReport
import com.genshin.gachahelper.ui.theme.FiveStarColor

@Composable
fun ReportScreen(viewModel: ReportViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            uiState.report == null -> {
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
                    // 报告标题
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "旅行者抽卡报告",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Genshin Gacha Helper",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 核心数据
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            ReportRow("累计抽数", "${report.totalPulls} 抽")
                            ReportRow("五星总数", "${report.totalFiveStars} 个", FiveStarColor)
                            ReportRow("平均出金", "${String.format("%.1f", report.avgPullsPerFiveStar)} 抽")
                            ReportRow("最高记录", "${report.worstLuck} 抽（最非）", MaterialTheme.colorScheme.error)
                            ReportRow("最低记录", "${report.bestLuck} 抽（最欧）", FiveStarColor)
                            ReportRow(
                                "UP 成功率",
                                "${String.format("%.1f", report.upSuccessRate * 100)}%"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 各卡池简要
                    report.characterPoolStats?.let { stats ->
                        MiniPoolReport("角色池", stats)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.character2PoolStats?.let { stats ->
                        MiniPoolReport("角色池-2", stats)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.weaponPoolStats?.let { stats ->
                        MiniPoolReport("武器池", stats)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.standardPoolStats?.let { stats ->
                        MiniPoolReport("常驻池", stats)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.novicePoolStats?.let { stats ->
                        MiniPoolReport("新手池", stats)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.chronicledPoolStats?.let { stats ->
                        MiniPoolReport("集录池", stats)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    report.stellarPoolStats?.let { stats ->
                        MiniPoolReport("千星奇域", stats)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.saveReportImage() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存图片")
                        }
                        Button(
                            onClick = { viewModel.shareReport() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("分享报告")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
fun MiniPoolReport(poolLabel: String, stats: com.genshin.gachahelper.analysis.PoolStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = poolLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${stats.totalPulls} 抽",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${stats.fiveStarCount} 个五星",
                    style = MaterialTheme.typography.bodySmall,
                    color = FiveStarColor,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "平均 ${String.format("%.1f", stats.avgPullsPerFiveStar)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
