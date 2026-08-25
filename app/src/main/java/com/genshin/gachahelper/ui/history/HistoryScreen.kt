package com.genshin.gachahelper.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.stickyHeader
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.ui.theme.FiveStarColor
import com.genshin.gachahelper.ui.theme.FourStarColor
import com.genshin.gachahelper.ui.theme.ThreeStarColor

/**
 * 列表项类型：把分页数据按日期分组后拍平成线性结构，便于插入粘性头。
 */
private sealed interface HistoryListItem {
    data class Header(val date: String) : HistoryListItem
    data class Record(val index: Int, val record: GachaRecordEntity) : HistoryListItem
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val filter by viewModel.filter.collectAsState()
    val searchQuery by viewModel.searchQueryInput.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val fiveStarIntervals by viewModel.fiveStarIntervals.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    val records: LazyPagingItems<GachaRecordEntity> = viewModel.records.collectAsLazyPagingItems()

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏
        SearchBar(query = searchQuery, onQueryChange = viewModel::setSearchQuery)

        // 筛选 Chips（选中态高亮）
        FilterChips(
            filter = filter,
            onPoolChange = viewModel::setPoolFilter,
            onRarityChange = viewModel::setRarityFilter
        )

        // 统计摘要栏
        SummaryBar(summary = summary)

        // 列表（日期分组 + 粘性头）
        val isEmpty = records.itemCount == 0 &&
            records.loadState.refresh is LoadState.NotLoading

        if (isEmpty) {
            EmptyState(modifier = Modifier.weight(1f))
        } else {
            // 把已加载的分页数据按日期拍平，在日期切换处插入分组头
            val listItems = remember(records.itemCount) {
                buildList<HistoryListItem> {
                    var lastDate: String? = null
                    for (i in 0 until records.itemCount) {
                        val record = records.peek(i) ?: continue
                        val date = if (record.time.length >= 10) record.time.substring(0, 10) else record.time
                        if (date != lastDate) {
                            add(HistoryListItem.Header(date))
                            lastDate = date
                        }
                        add(HistoryListItem.Record(i, record))
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                listItems.forEach { listItem ->
                    when (listItem) {
                        is HistoryListItem.Header -> {
                            val dayStat = dailyStats[listItem.date]
                            stickyHeader(key = "header_${listItem.date}") {
                                DateHeader(date = listItem.date, dayStat = dayStat)
                            }
                        }
                        is HistoryListItem.Record -> {
                            item(key = listItem.record.orderNumber) {
                                RecordItem(
                                    record = listItem.record,
                                    poolTypeName = viewModel.getPoolTypeName(listItem.record.poolType),
                                    interval = fiveStarIntervals[listItem.record.orderNumber]
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索栏：圆角 pill 样式的输入框。
 */
@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = { Text("搜索角色/武器名称") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清除")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp)
    )
}

/**
 * 筛选 Chips：卡池行 + 星级行，FilterChip 自带选中态高亮。
 */
@Composable
fun FilterChips(
    filter: HistoryFilter,
    onPoolChange: (Int?) -> Unit,
    onRarityChange: (Int?) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
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
                FilterChip(
                    selected = filter.poolType == null,
                    onClick = { onPoolChange(null) },
                    label = { Text("全部") }
                )
            }
            items(GachaType.entries.toList()) { pool ->
                FilterChip(
                    selected = filter.poolType == pool.value,
                    onClick = { onPoolChange(pool.value) },
                    label = { Text(pool.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                FilterChip(
                    selected = filter.rarity == null,
                    onClick = { onRarityChange(null) },
                    label = { Text("全部") }
                )
            }
            item {
                FilterChip(
                    selected = filter.rarity == 5,
                    onClick = { onRarityChange(5) },
                    label = { Text("五星", color = FiveStarColor) }
                )
            }
            item {
                FilterChip(
                    selected = filter.rarity == 4,
                    onClick = { onRarityChange(4) },
                    label = { Text("四星", color = FourStarColor) }
                )
            }
            item {
                FilterChip(
                    selected = filter.rarity == 3,
                    onClick = { onRarityChange(3) },
                    label = { Text("三星", color = ThreeStarColor) }
                )
            }
        }
    }
}

/**
 * 统计摘要栏：一行 4 格，总抽数/五星数/四星数/当前垫抽。
 */
@Composable
fun SummaryBar(summary: HistorySummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCell(
            label = "总抽数",
            value = summary.totalPulls.toString(),
            modifier = Modifier.weight(1f)
        )
        SummaryCell(
            label = "五星数",
            value = summary.fiveStarCount.toString(),
            valueColor = FiveStarColor,
            modifier = Modifier.weight(1f)
        )
        SummaryCell(
            label = "四星数",
            value = summary.fourStarCount.toString(),
            valueColor = FourStarColor,
            modifier = Modifier.weight(1f)
        )
        SummaryCell(
            label = "当前垫抽",
            value = summary.currentPity.toString(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SummaryCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 日期分组粘性头：显示日期 + 当天抽数 + 当天五星数（如有）。
 */
@Composable
fun DateHeader(date: String, dayStat: DayStat?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            val hasFive = dayStat != null && dayStat.fiveCount > 0
            val countText = buildString {
                append("${dayStat?.count ?: 0} 抽")
                if (hasFive) {
                    append(" · 五星 ${dayStat!!.fiveCount}")
                }
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                color = if (hasFive) FiveStarColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 记录项：圆角方块星级徽章 + 名称/池名/五星间隔标签 + HH:mm 时间。
 */
@Composable
fun RecordItem(record: GachaRecordEntity, poolTypeName: String, interval: Int?) {
    val rarityColor = when (record.rarity) {
        5 -> FiveStarColor
        4 -> FourStarColor
        else -> ThreeStarColor
    }
    // 时间简化为 HH:mm（time 形如 "yyyy-MM-dd HH:mm:ss"，取第 11-15 位）
    val timeText = if (record.time.length >= 16) record.time.substring(11, 16) else record.time.takeLast(5)

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
            // 星级徽章：圆角方块显示数字 5/4/3，配对应颜色边框
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = 2.dp,
                        color = rarityColor,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = record.rarity.toString(),
                    color = rarityColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.itemName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = poolTypeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 五星记录额外显示间隔标签
                    if (record.rarity == 5 && interval != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = FiveStarColor
                        ) {
                            Text(
                                text = "距上次五星 $interval 抽",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Text(
                text = timeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 空状态：无数据时显示"暂无抽卡记录"。
 */
@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "暂无抽卡记录",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
