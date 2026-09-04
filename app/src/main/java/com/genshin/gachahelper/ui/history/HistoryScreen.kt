package com.genshin.gachahelper.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.flatMap
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.ui.theme.FiveStarColor
import com.genshin.gachahelper.ui.theme.FourStarColor
import com.genshin.gachahelper.ui.theme.ThreeStarColor
import com.genshin.gachahelper.ui.theme.WishEmptyGlow
import com.genshin.gachahelper.ui.theme.WishShapes
import com.genshin.gachahelper.ui.theme.rarityColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 列表项类型：把分页数据按日期分组后拍平成线性结构，便于插入日期分组头。
 *
 * 改造说明（修复"历史数据显示不全"）：
 * - 不再在 remember{} 里用 LazyPagingItems.peek(i) 预扫描，因为 peek 不会触发下一页加载，
 *   且 remember 的快照只在 itemCount 改变时重建，首帧只加载 1~2 页时 UI 看起来像"缺数据"。
 * - 改为把上游 Flow<PagingData<GachaRecordEntity>> map 成 Flow<PagingData<HistoryListItem>>，
 *   PagingData 变换保留原始 Paging 语义，插入的 Header 不参与分页计数，仅对显示做装饰。
 */
sealed interface HistoryListItem {
    data class Header(val date: String) : HistoryListItem
    data class Record(val record: GachaRecordEntity) : HistoryListItem
}

/**
 * 把 PagingData<Record> 按相邻记录的日期差插入 Header，得到 PagingData<HistoryListItem>。
 *
 * 实现要点：
 * - PagingData 流每次产生一个新快照（refresh 或 filter 变更后）都会新建一个 lastDate 状态，
 *   因此"上一条记录的日期"只在同一份 PagingData 内复用，不会跨不同 Flow/Pager 污染。
 * - 同一份 PagingData 内，`map` 会按 items 在数据源中的顺序（time DESC，最新在前）依次访问，
 *   因此相邻 item 的比较是正确的——只要 `date != lastDate`，就说明进入了新的日期分组，
 *   在该条 Record 前插入一个 Header。
 * - 这里的 lambda 有状态是被官方 Paging 示例（Separations）采用的模式，
 *   每次 PagingData 新实例重置 lastDate，避免跨加载串味。
 */
private fun Flow<PagingData<GachaRecordEntity>>.withDateHeaders(): Flow<PagingData<HistoryListItem>> =
    map { pagingData ->
        var lastDate: String? = null
        pagingData.flatMap { record ->
            val date = if (record.time.length >= 10) record.time.substring(0, 10) else record.time
            buildList {
                if (lastDate != date) {
                    add(HistoryListItem.Header(date))
                    lastDate = date
                }
                add(HistoryListItem.Record(record))
            }
        }
    }

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val filter by viewModel.filter.collectAsState()
    val searchQuery by viewModel.searchQueryInput.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val fiveStarIntervals by viewModel.fiveStarIntervals.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()

    // 给 Paging 记录加上日期头（按每个 record 的 time 日期相邻性插入 Header）。
    // key 含 filter 三元组：filter 变更时重新 map（避免旧日期头残留）。
    val decoratedRecordsFlow: Flow<PagingData<HistoryListItem>> = remember(
        filter.poolType,
        filter.rarity,
        filter.searchQuery
    ) {
        viewModel.records.withDateHeaders()
    }
    val records: LazyPagingItems<HistoryListItem> =
        decoratedRecordsFlow.collectAsLazyPagingItems()

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(query = searchQuery, onQueryChange = viewModel::setSearchQuery)

        Spacer(modifier = Modifier.height(4.dp))

        FilterChips(
            filter = filter,
            onPoolChange = viewModel::setPoolFilter,
            onRarityChange = viewModel::setRarityFilter
        )

        Spacer(modifier = Modifier.height(8.dp))

        SummaryBar(summary = summary)

        val refresh = records.loadState.refresh
        val isEmpty = records.itemCount == 0 && refresh is LoadState.NotLoading

        when {
            refresh is LoadState.Loading && records.itemCount == 0 -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            isEmpty -> {
                EmptyState(modifier = Modifier.weight(1f))
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 用 Paging 的 items(count)，key/contentType 按类型区分：
                    // - Header: key=date, type="header"
                    // - Record: key=orderNumber, type="record"
                    items(
                        count = records.itemCount,
                        key = records.itemKey { item ->
                            when (item) {
                                is HistoryListItem.Header -> "header_${item.date}"
                                is HistoryListItem.Record -> "record_${item.record.orderNumber}"
                            }
                        },
                        contentType = records.itemContentType { item ->
                            when (item) {
                                is HistoryListItem.Header -> "history_header"
                                is HistoryListItem.Record -> "history_record"
                            }
                        }
                    ) { index ->
                        when (val item = records[index]) {
                            is HistoryListItem.Header -> {
                                val dayStat = dailyStats[item.date]
                                DateHeader(date = item.date, dayStat = dayStat)
                            }
                            is HistoryListItem.Record -> {
                                RecordItem(
                                    record = item.record,
                                    poolTypeName = viewModel.getPoolTypeName(item.record.poolType),
                                    interval = fiveStarIntervals[item.record.orderNumber]
                                )
                            }
                            null -> {
                                // Paging placeholder disabled，理论上不会走到这里
                                Spacer(modifier = Modifier.height(48.dp))
                            }
                        }
                    }

                    // Paging 追加加载状态：滚动到底部时显示"加载中 / 加载失败重试"
                    when (val append = records.loadState.append) {
                        is LoadState.Loading -> {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        is LoadState.Error -> {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "加载失败：${append.error.message?.take(40) ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        else -> {}
                    }

                    // 刷新失败：首屏顶部显示错误（如果首屏未加载但刷新报错）
                    if (refresh is LoadState.Error) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                shape = WishShapes.xs,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    modifier = Modifier.padding(12.dp),
                                    text = "刷新失败：${refresh.error.message ?: "未知错误"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================ 搜索栏 ============================

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = {
            Text(
                text = "搜索角色/武器名称",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        shape = WishShapes.lg,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    )
}

// ============================ 筛选 Chips ============================

@Composable
fun FilterChips(
    filter: HistoryFilter,
    onPoolChange: (Int?) -> Unit,
    onRarityChange: (Int?) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // 卡池筛选行
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = filter.poolType == null,
                    onClick = { onPoolChange(null) },
                    label = { Text("全部", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
            items(GachaType.entries.toList()) { pool ->
                FilterChip(
                    selected = filter.poolType == pool.value,
                    onClick = { onPoolChange(pool.value) },
                    label = {
                        Text(
                            text = pool.displayName,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 星级筛选行
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = filter.rarity == null,
                    onClick = { onRarityChange(null) },
                    label = { Text("全部", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
            item {
                FilterChip(
                    selected = filter.rarity == 5,
                    onClick = { onRarityChange(5) },
                    label = {
                        Text(
                            text = "五星",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (filter.rarity == 5) MaterialTheme.colorScheme.onPrimaryContainer
                            else FiveStarColor
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FiveStarColor.copy(alpha = 0.2f),
                        selectedLabelColor = FiveStarColor
                    )
                )
            }
            item {
                FilterChip(
                    selected = filter.rarity == 4,
                    onClick = { onRarityChange(4) },
                    label = {
                        Text(
                            text = "四星",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (filter.rarity == 4) MaterialTheme.colorScheme.onPrimaryContainer
                            else FourStarColor
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FourStarColor.copy(alpha = 0.2f),
                        selectedLabelColor = FourStarColor
                    )
                )
            }
            item {
                FilterChip(
                    selected = filter.rarity == 3,
                    onClick = { onRarityChange(3) },
                    label = {
                        Text(
                            text = "三星",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (filter.rarity == 3) MaterialTheme.colorScheme.onPrimaryContainer
                            else ThreeStarColor
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ThreeStarColor.copy(alpha = 0.2f),
                        selectedLabelColor = ThreeStarColor
                    )
                )
            }
        }
    }
}

// ============================ 统计摘要栏 ============================

@Composable
fun SummaryBar(summary: HistorySummary) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = WishShapes.md,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryCell(
                label = "总抽数",
                value = summary.totalPulls.toString(),
                modifier = Modifier.weight(1f)
            )
            // 分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
            SummaryCell(
                label = "五星",
                value = summary.fiveStarCount.toString(),
                valueColor = FiveStarColor,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
            SummaryCell(
                label = "四星",
                value = summary.fourStarCount.toString(),
                valueColor = FourStarColor,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
            SummaryCell(
                label = "当前垫抽",
                value = summary.currentPity.toString(),
                valueColor = if (summary.currentPity >= 60) FiveStarColor
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun SummaryCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================ 日期分组头 ============================

@Composable
fun DateHeader(date: String, dayStat: DayStat?) {
    val hasFive = dayStat != null && dayStat.fiveCount > 0
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        shape = WishShapes.xs,
        color = if (hasFive) FiveStarColor.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 日期前的小圆点指示器
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasFive) FiveStarColor
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = date,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hasFive) FiveStarColor
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            val countText = buildString {
                append("${dayStat?.count ?: 0} 抽")
                if (hasFive) {
                    append(" · 五星 ${dayStat!!.fiveCount}")
                }
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (hasFive) FiveStarColor
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================ 记录项 ============================

@Composable
fun RecordItem(record: GachaRecordEntity, poolTypeName: String, interval: Int?) {
    val rarityColor = rarityColor(record.rarity)
    val timeText = if (record.time.length >= 16) record.time.substring(11, 16) else record.time.takeLast(5)
    val borderAlpha = when (record.rarity) {
        5 -> 0.5f
        4 -> 0.35f
        else -> 0.15f
    }
    val badgeAlpha = if (record.rarity == 5) 0.22f else 0.15f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = WishShapes.xs,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = rarityColor.copy(alpha = borderAlpha)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 星级徽章：圆角方块，带背景色
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(WishShapes.xs)
                    .background(rarityColor.copy(alpha = badgeAlpha))
                    .border(
                        width = 1.5.dp,
                        color = rarityColor,
                        shape = WishShapes.xs
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = record.rarity.toString(),
                    color = rarityColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.itemName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (record.rarity == 5) rarityColor
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = poolTypeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 五星记录额外显示间隔标签
                    if (record.rarity == 5 && interval != null) {
                        Surface(
                            shape = WishShapes.pill,
                            color = FiveStarColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "距上次 $interval 抽",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = FiveStarColor,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================ 空状态 ============================

@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    WishEmptyGlow(modifier = modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "暂无抽卡记录",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "先去首页同步或导入数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
