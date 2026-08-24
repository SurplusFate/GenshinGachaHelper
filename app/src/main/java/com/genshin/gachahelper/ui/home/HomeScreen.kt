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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.analysis.PoolStats
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

    if (!uiState.isLoggedIn) {
        // 未登录引导
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
                text = "授权米游社，自动同步抽卡记录，分析你的抽卡运气",
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
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 账号信息
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = uiState.nickname ?: "旅行者",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "UID: ${uiState.uid}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // 同步按钮
        item {
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
        }

        // 角色池
        item {
            uiState.characterStats?.let {
                PoolCard(poolStats = it, poolLabel = "角色池")
            } ?: EmptyPoolCard("角色池")
        }

        // 角色池-2
        item {
            uiState.character2Stats?.let {
                PoolCard(poolStats = it, poolLabel = "角色池-2")
            } ?: EmptyPoolCard("角色池-2")
        }

        // 武器池
        item {
            uiState.weaponStats?.let {
                PoolCard(poolStats = it, poolLabel = "武器池")
            } ?: EmptyPoolCard("武器池")
        }

        // 常驻池
        item {
            uiState.standardStats?.let {
                PoolCard(poolStats = it, poolLabel = "常驻池")
            } ?: EmptyPoolCard("常驻池")
        }

        // 新手池
        item {
            uiState.noviceStats?.let {
                PoolCard(poolStats = it, poolLabel = "新手池")
            } ?: EmptyPoolCard("新手池")
        }

        // 集录池
        item {
            uiState.chronicledStats?.let {
                PoolCard(poolStats = it, poolLabel = "集录池")
            } ?: EmptyPoolCard("集录池")
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun PoolCard(poolStats: PoolStats, poolLabel: String) {
    val pityPercent = (poolStats.currentPity.toFloat() / poolStats.pityCeiling).coerceIn(0f, 1f)
    val pityLeft = poolStats.pityCeiling - poolStats.currentPity

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = poolLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "累计 ${poolStats.totalPulls} 抽",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 垫抽进度
            Text(
                text = "当前垫抽：${poolStats.currentPity} / ${poolStats.pityCeiling}",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { pityPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = FiveStarColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "距离保底：${pityLeft} 抽",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 五星信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "五星数量",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${poolStats.fiveStarCount} 个",
                        style = MaterialTheme.typography.titleMedium,
                        color = FiveStarColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "平均出金",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${String.format("%.1f", poolStats.avgPullsPerFiveStar)} 抽",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 最近五星
            if (poolStats.lastFiveStarName != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "最近五星：${poolStats.lastFiveStarName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FiveStarColor
                )
            }
        }
    }
}

@Composable
fun EmptyPoolCard(poolLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = poolLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "暂无数据，点击上方同步按钮获取抽卡记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
