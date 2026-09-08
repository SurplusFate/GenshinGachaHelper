package com.genshin.gachahelper.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.analysis.GachaReport
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.data.local.dao.GachaRecordDao.DailyStat
import com.genshin.gachahelper.data.local.dao.GachaRecordDao.ItemCount
import com.genshin.gachahelper.ui.navigation.Screen
import com.genshin.gachahelper.ui.theme.FiveStarColor
import com.genshin.gachahelper.ui.theme.FiveStarGlowInner
import com.genshin.gachahelper.ui.theme.FourStarColor
import com.genshin.gachahelper.ui.theme.ThreeStarColor
import com.genshin.gachahelper.ui.theme.WishEmptyGlow
import com.genshin.gachahelper.ui.theme.WishShapes
import com.genshin.gachahelper.ui.theme.wishDivider
import com.genshin.gachahelper.ui.theme.wishSuccess
import com.genshin.gachahelper.ui.theme.wishTextHigh
import com.genshin.gachahelper.ui.theme.wishTextMid
import kotlinx.coroutines.launch
import java.time.YearMonth

@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    when {
        uiState.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        !uiState.hasData -> {
            WishEmptyGlow(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
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
        }
        else -> {
            uiState.report?.let { report ->
                StatsScrollContent(
                    report = report,
                    timeline = uiState.fiveStarTimeline,
                    itemCollection = uiState.itemCollection,
                    dailyStats = uiState.dailyStats,
                    navController = navController
                )
            }
        }
    }
}

// ============================ 主滚动布局 ============================

@Composable
private fun StatsScrollContent(
    report: GachaReport,
    timeline: List<FiveStarTimelineItem>,
    itemCollection: List<ItemCount>,
    dailyStats: List<DailyStat>,
    navController: NavController
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 各区块在 LazyColumn 中的起始 item 索引
    // 0 = 概览, 1 = 时间轴, 2 = 图鉴, 3 = 日历
    val sectionStartIndices = listOf(0, 1, 2, 3)
    val sectionNames = listOf("概览", "时间轴", "图鉴", "日历")

    // 根据滚动位置自动高亮当前区块
    val activeSectionIndex by remember {
        derivedStateOf {
            val firstVisible = listState.firstVisibleItemIndex
            var active = 0
            sectionStartIndices.forEachIndexed { i, idx ->
                if (firstVisible >= idx) active = i
            }
            active
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 52.dp, bottom = 24.dp)
        ) {
            // ===== 区块 1: 概览 =====
            item(key = "overview") {
                SectionTag("概览")
                OverviewContent(report = report, navController = navController)
            }

            // ===== 区块 2: 时间轴 =====
            item(key = "timeline") {
                SectionTag("时间轴")
                TimelineContent(timeline = timeline)
            }

            // ===== 区块 3: 图鉴 =====
            item(key = "collection") {
                SectionTag("图鉴")
                CollectionContent(items = itemCollection)
            }

            // ===== 区块 4: 日历 =====
            item(key = "calendar") {
                SectionTag("日历")
                CalendarContent(dailyStats = dailyStats)
            }
        }

        // 吸顶导航栏（始终可见的浮层）
        StatsNavBar(
            sectionNames = sectionNames,
            activeIndex = activeSectionIndex,
            onTabClick = { index ->
                coroutineScope.launch {
                    listState.animateScrollToItem(sectionStartIndices[index])
                }
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

// ============================ 吸顶导航栏 ============================

@Composable
private fun StatsNavBar(
    sectionNames: List<String>,
    activeIndex: Int,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            sectionNames.forEachIndexed { index, name ->
                NavTab(
                    title = name,
                    isSelected = activeIndex == index,
                    onClick = { onTabClick(index) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(WishShapes.xs)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .width(if (isSelected) 24.dp else 0.dp)
                .height(2.dp)
                .clip(WishShapes.pill)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else Color.Transparent
                )
        )
    }
}

// ============================ 区块标签 ============================

@Composable
private fun SectionTag(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

// ============================ 区块 1: 概览 ============================

@Composable
private fun OverviewContent(report: GachaReport, navController: NavController) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // 总览卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = WishShapes.md,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "累计抽卡",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${report.totalPulls}",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = " 抽",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatMini(
                        label = "五星总数",
                        value = "${report.totalFiveStars}",
                        valueColor = FiveStarColor,
                        modifier = Modifier.weight(1f)
                    )
                    StatMini(
                        label = "平均出金",
                        value = "${String.format("%.1f", report.avgPullsPerFiveStar)} 抽",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 运气分析卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = WishShapes.md,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "运气分析",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 运气条形图
                LuckBarRow(
                    label = "最欧",
                    value = report.bestLuck,
                    maxValue = 90,
                    color = wishSuccess()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LuckBarRow(
                    label = "平均",
                    value = report.avgPullsPerFiveStar.toInt(),
                    maxValue = 90,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                LuckBarRow(
                    label = "最非",
                    value = report.worstLuck,
                    maxValue = 90,
                    color = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatMini(
                        label = "UP成功率",
                        value = "${String.format("%.0f", report.upSuccessRate * 100)}%",
                        modifier = Modifier.weight(1f)
                    )
                    StatMini(
                        label = "总五星",
                        value = "${report.totalFiveStars}",
                        valueColor = FiveStarColor,
                        modifier = Modifier.weight(1f)
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

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { navController.navigate(Screen.Report.route) },
            modifier = Modifier.fillMaxWidth(),
            shape = WishShapes.lg
        ) {
            Text("生成抽卡报告")
        }
    }
}

@Composable
private fun LuckBarRow(
    label: String,
    value: Int,
    maxValue: Int,
    color: Color
) {
    val percent = (value.toFloat() / maxValue).coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(WishShapes.pill)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent)
                    .height(8.dp)
                    .clip(WishShapes.pill)
                    .background(color)
            )
        }
        Text(
            text = "${value}抽",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

// ============================ 区块 2: 时间轴 ============================

@Composable
private fun TimelineContent(timeline: List<FiveStarTimelineItem>) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (timeline.isEmpty()) {
            EmptyState(message = "暂无五星记录")
            return
        }

        val avgInterval = timeline.map { it.interval }.average()
        val bestInterval = timeline.minOf { it.interval }
        val worstInterval = timeline.maxOf { it.interval }

        Text(
            text = "共 ${timeline.size} 个五星，从左到右按出金时间排列",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = 4.dp, vertical = 4.dp
            )
        ) {
            items(timeline) { item ->
                TimelineNode(item = item)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 底部统计
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            TimelineStat(
                value = "${timeline.size}",
                label = "总五星",
                color = FiveStarColor
            )
            TimelineStat(
                value = String.format("%.1f", avgInterval),
                label = "平均",
                color = MaterialTheme.colorScheme.primary
            )
            TimelineStat(
                value = "$bestInterval",
                label = "最欧",
                color = wishSuccess()
            )
            TimelineStat(
                value = "$worstInterval",
                label = "最非",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun TimelineStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TimelineNode(item: FiveStarTimelineItem) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 角色名
        Text(
            text = item.itemName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = item.time.take(10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        val lineColor = wishDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 3.dp.toPx()
                )
            }
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(
                        brush = Brush.radialGradient(
                            listOf(FiveStarGlowInner, FiveStarColor)
                        ),
                        shape = CircleShape
                    )
                    .border(1.5.dp, FiveStarColor.copy(alpha = 0.7f), CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "${item.interval}抽",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = FiveStarColor
        )
        Text(
            text = item.poolName,
            style = MaterialTheme.typography.labelSmall,
            color = wishTextMid(),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================ 区块 3: 图鉴 ============================

@Composable
private fun CollectionContent(items: List<ItemCount>) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (items.isEmpty()) {
            EmptyState(message = "暂无物品记录")
            return
        }

        val fiveStars = items.filter { it.rarity == 5 }
        val fourStars = items.filter { it.rarity == 4 }
        val threeStars = items.filter { it.rarity == 3 }

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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WishShapes.xs,
        border = BorderStroke(1.5.dp, borderColor),
        color = borderColor.copy(alpha = 0.08f)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = item.itemName,
                style = MaterialTheme.typography.labelSmall,
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

// ============================ 区块 4: 日历 ============================

@Composable
private fun CalendarContent(dailyStats: List<DailyStat>) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (dailyStats.isEmpty()) {
            EmptyState(message = "暂无日历数据")
            return
        }

        val statsMap = remember(dailyStats) {
            dailyStats.associateBy { it.date }
        }

        var currentMonth by remember {
            mutableStateOf(
                runCatching {
                    val latestDate = dailyStats.maxOf { it.date }
                    YearMonth.parse(latestDate.substring(0, 7))
                }.getOrDefault(YearMonth.now())
            )
        }

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
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "下一月")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 星期表头
        val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
        Row(modifier = Modifier.fillMaxWidth()) {
            weekDays.forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 月份格子计算
        val firstDayOfMonth = currentMonth.atDay(1)
        val daysInMonth = currentMonth.lengthOfMonth()
        val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value
        val leadingBlanks = firstDayOfWeek - 1

        val cells: List<Int?> = remember(currentMonth) {
            buildList {
                repeat(leadingBlanks) { add(null) }
                for (day in 1..daysInMonth) add(day)
            }
        }

        // 7 列网格（用 Row + chunked 替代 LazyVerticalGrid，避免嵌套冲突）
        cells.chunked(7).forEach { weekRow ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                weekRow.forEach { day ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (day == null) {
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
                // 不足 7 个时补齐
                repeat(7 - weekRow.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))
        CalendarLegend()
    }
}

@Composable
private fun CalendarDayCell(day: Int, count: Int, fiveCount: Int) {
    val hasFiveStar = fiveCount > 0
    val backgroundColor = when {
        hasFiveStar -> FiveStarColor.copy(alpha = 0.28f)
        count >= 20 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        count >= 10 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        count >= 1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val dayTextColor = wishTextHigh()
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(WishShapes.xs)
            .background(backgroundColor)
            .then(
                if (hasFiveStar) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = FiveStarColor,
                        shape = WishShapes.xs
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
                color = dayTextColor
            )
            if (count > 0) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = dayTextColor
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
        LegendBox(color = FiveStarColor.copy(alpha = 0.28f))
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
            .clip(WishShapes.xs)
            .background(color)
    )
}

// ============================ 公共组件 ============================

@Composable
private fun EmptyState(message: String) {
    WishEmptyGlow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatMini(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

@Composable
fun PoolStatCard(poolLabel: String, stats: PoolStats) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WishShapes.md,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = poolLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "五星 ${stats.fiveStarCount} · 垫抽 ${stats.currentPity}/${stats.pityCeiling}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stats.currentPity >= 60) FiveStarColor
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatMini("总抽数", "${stats.totalPulls}")
                StatMini("五星", "${stats.fiveStarCount}", valueColor = FiveStarColor)
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
