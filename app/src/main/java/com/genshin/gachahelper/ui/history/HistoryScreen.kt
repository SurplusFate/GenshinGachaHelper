package com.genshin.gachahelper.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.ui.theme.FiveStarColor
import com.genshin.gachahelper.ui.theme.FourStarColor
import com.genshin.gachahelper.ui.theme.ThreeStarColor

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val filter by viewModel.filter.collectAsState()
    val records: LazyPagingItems<GachaRecordEntity> = viewModel.records.collectAsLazyPagingItems()

    Column(modifier = Modifier.fillMaxSize()) {
        // 筛选栏
        FilterChips(
            filter = filter,
            onPoolChange = { viewModel.setPoolFilter(it) },
            onRarityChange = { viewModel.setRarityFilter(it) }
        )

        // 列表
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(records.itemCount) { index ->
                records[index]?.let { record ->
                    RecordItem(
                        record = record,
                        poolTypeName = viewModel.getPoolTypeName(record.poolType)
                    )
                }
            }
        }
    }
}

@Composable
fun FilterChips(
    filter: HistoryFilter,
    onPoolChange: (Int?) -> Unit,
    onRarityChange: (Int?) -> Unit
) {
    Column(modifier = Modifier.padding(12.dp)) {
        // 卡池筛选
        Text(
            text = "卡池",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                AssistChip(
                    onClick = { onPoolChange(null) },
                    label = { Text("全部") }
                )
            }
            items(GachaType.entries.toList()) { pool ->
                AssistChip(
                    onClick = { onPoolChange(pool.value) },
                    label = { Text(pool.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 星级筛选
        Text(
            text = "星级",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                AssistChip(
                    onClick = { onRarityChange(null) },
                    label = { Text("全部") }
                )
            }
            item {
                AssistChip(
                    onClick = { onRarityChange(5) },
                    label = { Text("五星", color = FiveStarColor) }
                )
            }
            item {
                AssistChip(
                    onClick = { onRarityChange(4) },
                    label = { Text("四星", color = FourStarColor) }
                )
            }
        }
    }
}

@Composable
fun RecordItem(record: GachaRecordEntity, poolTypeName: String) {
    val rarityColor = when (record.rarity) {
        5 -> FiveStarColor
        4 -> FourStarColor
        else -> ThreeStarColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 星级标识
            Text(
                text = "★".repeat(record.rarity),
                color = rarityColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.itemName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = poolTypeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = record.time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
