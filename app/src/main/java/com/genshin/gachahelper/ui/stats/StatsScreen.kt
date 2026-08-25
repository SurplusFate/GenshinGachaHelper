package com.genshin.gachahelper.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.analysis.GachaReport
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.data.local.dao.GachaRecordDao.DailyStat
import com.genshin.gachahelper.data.local.dao.GachaRecordDao.ItemCount
import com.genshin.gachahelper.ui.theme.FiveStarColor
import com.genshin.gachahelper.ui.theme.FourStarColor
import com.genshin.gachahelper.ui.theme.ThreeStarColor
import java.time.YearMonth

@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("概览", "时间轴", "图鉴", "日历")

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
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title) }
                            )
                        }
                    }

                    when (selectedTab) {
                        0 -> OverviewTab(
                            report = uiState.report!!,
                            navController = navController
                        )
                        1 -> TimelineTab(timeline = uiState.fiveStarTimeline)
                        2 -> CollectionTab(items = uiState.itemCollection)
                        3 -> CalendarTab(dailyStats = uiState.dailyStats)
                    }
                }
            }
        }
    }
}

// ===================== Tab 1: 概览 =====================

@Composable
private fun OverviewTab(report: GachaReport, navController: NavController) {
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

// ===================== Tab 2: 时间轴 =====================

@Composable
private fun TimelineTab(timeline: List<FiveStarTimelineItem>) {
    if (timeline.isEmpty()) {
        EmptyState(message = "暂无五星记录")
        return
    }

    val avgInterval = timeline.map { it.interval }.average()
    val bestInterval = timeline.minOf { it.interval }
    val worstInterval = timeline.maxOf { it.interval }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "五星出金时间轴",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "共 ${timeline.size} 个五星，从左到右按出金时间排列",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 4.dp
            )
        ) {
            items(timeline) { item ->
                TimelineNode(item = item)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 底部统计：平均出金 + 总五星数
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatMini(
                    label = "总五星数",
                    value = "${timeline.size}",
                    valueColor = FiveStarColor
                )
                StatMini(
                    label = "平均出金",
                    value = "${String.format("%.1f", avgInterval)} 抽"
                )
                StatMini(
                    label = "最欧",
                    value = "$bestInterval 抽",
                    valueColor = FiveStarColor
                )
                StatMini(
                    label = "最非",
                    value = "$worstInterval 抽",
                    valueColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun TimelineNode(item: FiveStarTimelineItem) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 角色名
        Text(
            text = item.itemName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 出金日期
        Text(
            text = item.time.take(10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 圆点 + 连接线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
            contentAlignment = Alignment.Center
        ) {
            // 横贯整个节点的连接线，多个节点拼接成连续时间轴
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height / 2f
                drawLine(
                    color = FiveStarColor.copy(alpha = 0.5f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 3.dp.toPx()
                )
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(FiveStarColor)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 间隔抽数
        Text(
            text = "${item.interval}抽",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = FiveStarColor
        )
        // 所属卡池
        Text(
            text = item.poolName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ===================== Tab 3: 图鉴 =====================

@Composable
private fun CollectionTab(items: List<ItemCount>) {
    if (items.isEmpty()) {
        EmptyState(message = "暂无物品记录")
        return
    }

    val fiveStars = items.filter { it.rarity == 5 }
    val fourStars = items.filter { it.rarity == 4 }
    val threeStars = items.filter { it.rarity == 3 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (fiveStars.isNotEmpty()) {
            RaritySection(title = "五星", items = fiveStars, color = FiveStarColor)
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (fourStars.isNotEmpty()) {
            RaritySection(title = "四星", items = fourStars, color = FourStarColor)
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (threeStars.isNotEmpty()) {
            RaritySection(title = "三星", items = threeStars, color = ThreeStarColor)
        }
    }
}

@Composable
private fun RaritySection(title: String, items: List<ItemCount>, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$title（${items.size} 种）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3 列网格
        items.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        CollectionCard(item = item, borderColor = color)
                    }
                }
                // 不足 3 个时用空占位补齐，保持等宽对齐
                repeat(3 - rowItems.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CollectionCard(item: ItemCount, borderColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = borderColor.copy(alpha = 0.08f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = item.itemName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "x${item.count}",
                style = MaterialTheme.typography.labelSmall,
                color = borderColor
            )
        }
    }
}

// ===================== Tab 4: 日历 =====================

@Composable
private fun CalendarTab(dailyStats: List<DailyStat>) {
    if (dailyStats.isEmpty()) {
        EmptyState(message = "暂无日历数据")
        return
    }

    // date("yyyy-MM-dd") -> DailyStat
    val statsMap = remember(dailyStats) {
        dailyStats.associateBy { it.date }
    }

    // 默认展示数据中最近一天所在月份
    var currentMonth by remember {
        mutableStateOf(
            runCatching {
                val latestDate = dailyStats.maxOf { it.date }
                YearMonth.parse(latestDate.substring(0, 7))
            }.getOrDefault(YearMonth.now())
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 月份切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "上一月")
            }
            Text(
                text = "${currentMonth.year} 年 ${currentMonth.monthValue} 月",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "下一月")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 星期表头（周一到周日）
        val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
        Row(modifier = Modifier.fillMaxWidth()) {
            weekDays.forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 月份格子计算
        val firstDayOfMonth = currentMonth.atDay(1)
        val daysInMonth = currentMonth.lengthOfMonth()
        // 周一=1 ... 周日=7
        val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value
        val leadingBlanks = firstDayOfWeek - 1

        // 构建格子列表：null 表示月初空白，Int 表示该月第几天
        val cells: List<Int?> = buildList {
            repeat(leadingBlanks) { add(null) }
            for (day in 1..daysInMonth) add(day)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            gridItems(cells) { day ->
                if (day == null) {
                    // 月初空白格
                    Box(modifier = Modifier.aspectRatio(1f))
                } else {
                    val dateStr = String.format(
                        "%04d-%02d-%02d",
                        currentMonth.year,
                        currentMonth.monthValue,
                        day
                    )
                    val stat = statsMap[dateStr]
                    CalendarDayCell(
                        day = day,
                        count = stat?.count ?: 0,
                        fiveCount = stat?.fiveCount ?: 0
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 图例
        CalendarLegend()
    }
}

@Composable
private fun CalendarDayCell(day: Int, count: Int, fiveCount: Int) {
    val backgroundColor = when {
        count >= 20 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        count >= 10 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        count >= 1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val hasFiveStar = fiveCount > 0
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .then(
                if (hasFiveStar) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = FiveStarColor,
                        shape = RoundedCornerShape(6.dp)
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (count > 0) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CalendarLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "少",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        LegendBox(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        LegendBox(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        LegendBox(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        LegendBox(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "多",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(16.dp))

        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(FiveStarColor)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "五星",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LegendBox(color: Color) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
}

// ===================== 公共组件 =====================

@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatMini(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
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
