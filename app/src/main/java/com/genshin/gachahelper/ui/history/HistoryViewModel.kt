package com.genshin.gachahelper.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryFilter(
    val poolType: Int? = null, // null = 全部
    val rarity: Int? = null,    // null = 全部
    val searchQuery: String = "" // 空字符串 = 不搜索
)

/**
 * 历史页统计摘要
 */
data class HistorySummary(
    val totalPulls: Int = 0,
    val fiveStarCount: Int = 0,
    val fourStarCount: Int = 0,
    val currentPity: Int = 0
)

/**
 * 单日抽卡统计（供日期分组粘性头使用）
 */
data class DayStat(
    val date: String,
    val count: Int,
    val fiveCount: Int
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val gachaRepository: GachaRepository,
    private val authRepository: AuthRepository,
    private val sessionEventBus: SessionEventBus,
    private val statsCalculator: GachaStatsCalculator
) : ViewModel() {

    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    // 搜索输入（原始、未去抖）——供搜索框即时回显
    private val _searchQueryInput = MutableStateFlow("")
    val searchQueryInput: StateFlow<String> = _searchQueryInput.asStateFlow()

    // 刷新触发器：每次收到事件就自增，使 flatMapLatest 重新创建 Pager
    private val _refreshTrigger = MutableStateFlow(0)

    // 五星出金间隔：key = orderNumber，value = 距上一条五星的抽数
    private val _fiveStarIntervals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val fiveStarIntervals: StateFlow<Map<String, Int>> = _fiveStarIntervals.asStateFlow()

    // 统计摘要
    private val _summary = MutableStateFlow(HistorySummary())
    val summary: StateFlow<HistorySummary> = _summary.asStateFlow()

    // 每日统计（供日期分组粘性头使用）
    private val _dailyStats = MutableStateFlow<Map<String, DayStat>>(emptyMap())
    val dailyStats: StateFlow<Map<String, DayStat>> = _dailyStats.asStateFlow()

    val records: Flow<PagingData<GachaRecordEntity>> = _refreshTrigger
        .flatMapLatest { _ ->
            _filter.flatMapLatest { filter ->
                val uid = runCatching { authRepository.getUid() }.getOrNull()
                val account = runCatching { gachaRepository.getActiveAccount(uid) }.getOrNull()

                if (account == null) {
                    flowOf(PagingData.empty())
                } else {
                    val accountId = account.id
                    val query = filter.searchQuery
                    val hasSearch = query.isNotBlank()
                    val pagingSourceFactory = when {
                        // ===== 有搜索 =====
                        hasSearch && filter.poolType != null && filter.rarity != null -> {
                            { gachaRepository.getRecordsPagedByPoolAndRarityAndSearch(accountId, filter.poolType, filter.rarity, query) }
                        }
                        hasSearch && filter.poolType != null -> {
                            { gachaRepository.getRecordsPagedByPoolAndSearch(accountId, filter.poolType, query) }
                        }
                        hasSearch && filter.rarity != null -> {
                            { gachaRepository.getRecordsPagedByRarityAndSearch(accountId, filter.rarity, query) }
                        }
                        hasSearch -> {
                            { gachaRepository.getRecordsPagedBySearch(accountId, query) }
                        }
                        // ===== 无搜索：保持现有逻辑 =====
                        filter.poolType != null && filter.rarity != null -> {
                            { gachaRepository.getRecordsPagedByPoolAndRarity(accountId, filter.poolType, filter.rarity) }
                        }
                        filter.poolType != null -> {
                            { gachaRepository.getRecordsPagedByPool(accountId, filter.poolType) }
                        }
                        filter.rarity != null -> {
                            { gachaRepository.getRecordsPagedByRarity(accountId, filter.rarity) }
                        }
                        else -> {
                            { gachaRepository.getAllRecordsPaged(accountId) }
                        }
                    }

                    Pager(
                        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                        pagingSourceFactory = pagingSourceFactory
                    ).flow.cachedIn(viewModelScope)
                }
            }
        }

    init {
        // 搜索去抖：300ms 内无新输入才把查询写入 filter，触发 Pager 重建
        viewModelScope.launch {
            _searchQueryInput
                .debounce(300)
                .distinctUntilChanged()
                .collect { query ->
                    _filter.value = _filter.value.copy(searchQuery = query)
                }
        }

        // 监听全局会话事件，收到后触发 Pager 重建 + 刷新五星间隔/摘要
        viewModelScope.launch {
            sessionEventBus.events.collect { event ->
                when (event) {
                    SessionEvent.LoginCompleted,
                    SessionEvent.DataImported,
                    SessionEvent.DataSynced -> {
                        _refreshTrigger.value++
                        refreshStats()
                    }

                    SessionEvent.LogoutCompleted,
                    SessionEvent.DataCleared -> {
                        _filter.value = HistoryFilter()
                        _searchQueryInput.value = ""
                        _refreshTrigger.value++
                        refreshStats()
                    }
                }
            }
        }

        // 初始加载统计
        refreshStats()
    }

    /**
     * 计算一组记录中的五星出金间隔，结果写入 [intervals] Map。
     * 按 orderNumber 正序排列，每遇到五星计算距上一条五星的位置差。
     * 与 GachaStatsCalculator.calculateFiveStarIntervals 完全一致。
     */
    private fun computeFiveStarIntervals(
        records: List<GachaRecordEntity>,
        intervals: MutableMap<String, Int>
    ) {
        if (records.isEmpty()) return
        val sorted = records.sortedBy { it.orderNumber.toLongOrNull() ?: 0L }
        var lastFiveStarIndex = -1
        for ((index, record) in sorted.withIndex()) {
            if (record.rarity == 5) {
                val interval = if (lastFiveStarIndex == -1) index + 1
                else index - lastFiveStarIndex
                intervals[record.orderNumber] = interval
                lastFiveStarIndex = index
            }
        }
    }

    fun setPoolFilter(poolType: Int?) {
        _filter.value = _filter.value.copy(poolType = poolType)
    }

    fun setRarityFilter(rarity: Int?) {
        _filter.value = _filter.value.copy(rarity = rarity)
    }

    fun setSearchQuery(query: String) {
        _searchQueryInput.value = query
    }

    fun getPoolTypeName(poolType: Int): String {
        return GachaType.fromValue(poolType).displayName
    }

    /**
     * 加载并计算：五星出金间隔、统计摘要、每日统计。
     * 在 init、登录/导入/同步/登出/清除事件时调用。
     *
     * 间隔计算采用"更简单方案"：合并各池全部记录按 orderNumber 正序排列，
     * 遍历记录，每遇到五星就计算距上一个五星的位置差（抽数）。
     */
    private fun refreshStats() {
        viewModelScope.launch {
            val uid = runCatching { authRepository.getUid() }.getOrNull()
            val account = runCatching { gachaRepository.getActiveAccount(uid) }.getOrNull()

            if (account == null) {
                _fiveStarIntervals.value = emptyMap()
                _summary.value = HistorySummary()
                _dailyStats.value = emptyMap()
                return@launch
            }

            val accountId = account.id

            // 各池记录
            val characterRecords = gachaRepository.getRecordsByPool(accountId, GachaType.CHARACTER.value)
            val character2Records = gachaRepository.getRecordsByPool(accountId, GachaType.CHARACTER_2.value)
            val weaponRecords = gachaRepository.getRecordsByPool(accountId, GachaType.WEAPON.value)
            val standardRecords = gachaRepository.getRecordsByPool(accountId, GachaType.STANDARD.value)
            val noviceRecords = gachaRepository.getRecordsByPool(accountId, GachaType.NOVICE.value)
            val chronicledRecords = gachaRepository.getRecordsByPool(accountId, GachaType.CHRONICLED.value)

            val allRecords = characterRecords + character2Records + weaponRecords +
                standardRecords + noviceRecords + chronicledRecords

            // 五星间隔：与 GachaStatsCalculator 保持一致
            // 角色池 301+400 共享保底，合并后按 time 正序计算间隔；其他池单独算
            val intervals = mutableMapOf<String, Int>()
            computeFiveStarIntervals(characterRecords + character2Records, intervals)
            computeFiveStarIntervals(weaponRecords, intervals)
            computeFiveStarIntervals(standardRecords, intervals)
            computeFiveStarIntervals(noviceRecords, intervals)
            computeFiveStarIntervals(chronicledRecords, intervals)
            _fiveStarIntervals.value = intervals

            // 统计摘要：垫抽取各池中的最大值（用户最关心的是最接近保底的池）
            val pityValues = listOf(
                statsCalculator.calculateCurrentPity(characterRecords + character2Records),
                statsCalculator.calculateCurrentPity(weaponRecords),
                statsCalculator.calculateCurrentPity(standardRecords),
                statsCalculator.calculateCurrentPity(noviceRecords),
                statsCalculator.calculateCurrentPity(chronicledRecords)
            ).filter { it > 0 }

            _summary.value = HistorySummary(
                totalPulls = allRecords.size,
                fiveStarCount = allRecords.count { it.rarity == 5 },
                fourStarCount = allRecords.count { it.rarity == 4 },
                currentPity = pityValues.maxOrNull() ?: 0
            )

            // 每日统计（按 date 分组）
            _dailyStats.value = allRecords
                .groupBy { if (it.time.length >= 10) it.time.substring(0, 10) else it.time }
                .mapValues { (date, records) ->
                    DayStat(
                        date = date,
                        count = records.size,
                        fiveCount = records.count { it.rarity == 5 }
                    )
                }
        }
    }
}
