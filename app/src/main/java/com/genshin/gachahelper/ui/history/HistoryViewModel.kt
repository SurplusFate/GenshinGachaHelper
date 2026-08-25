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
        // 搜索去抖：300ms 内无新输入才把查询写入 filter，触发 Pager 重建 + stats 刷新
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
                        // filter 保持不变，基于当前 filter 重新计算 stats
                        refreshStatsForCurrentAccountAndFilter()
                    }

                    SessionEvent.LogoutCompleted,
                    SessionEvent.DataCleared -> {
                        _filter.value = HistoryFilter()
                        _searchQueryInput.value = ""
                        _refreshTrigger.value++
                        refreshStatsForCurrentAccountAndFilter()
                    }
                }
            }
        }

        // 核心：每次 filter 变化，重新计算 stats（保证顶部摘要/间隔/每日统计与下方列表一致）
        viewModelScope.launch {
            _filter
                .collect { _ ->
                    refreshStatsForCurrentAccountAndFilter()
                }
        }
    }

    /**
     * 基于当前登录账号 + 当前 filter 重算 summary / fiveStarIntervals / dailyStats，
     * 写入各 StateFlow 供 UI 展示。此方法与 Paging records 使用同一 filter，保证一致。
     */
    private suspend fun refreshStatsForCurrentAccountAndFilter() {
        val uid = runCatching { authRepository.getUid() }.getOrNull()
        val account = runCatching { gachaRepository.getActiveAccount(uid) }.getOrNull()

        if (account == null) {
            _fiveStarIntervals.value = emptyMap()
            _summary.value = HistorySummary()
            _dailyStats.value = emptyMap()
            return
        }
        computeStats(account.id, _filter.value)
    }

    /**
     * 计算一组记录中的五星出金间隔，结果写入 [intervals] Map。
     * 按 time 正序排列，每遇到五星计算距上一条五星的位置差。
     * 与 GachaStatsCalculator.calculateFiveStarIntervals 逻辑一致。
     * @param pityCeiling 保底上限，超过此值的间隔不写入（数据不完整导致的不可能值）
     */
    private fun computeFiveStarIntervals(
        records: List<GachaRecordEntity>,
        intervals: MutableMap<String, Int>,
        pityCeiling: Int = 0
    ) {
        if (records.isEmpty()) return
        val sorted = records.sortedBy { it.time }
        var lastFiveStarIndex = -1
        for ((index, record) in sorted.withIndex()) {
            if (record.rarity == 5) {
                val interval = if (lastFiveStarIndex == -1) index + 1
                else index - lastFiveStarIndex
                // 过滤超过保底上限的不可能值（数据不完整时缺少五星会导致间隔 > 90）
                if (pityCeiling <= 0 || interval <= pityCeiling) {
                    intervals[record.orderNumber] = interval
                }
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
     * 过滤+计算：根据 [filter] 从各池记录中筛选出符合条件的记录，
     * 再计算 summary / fiveStarIntervals / dailyStats 并写入 StateFlow。
     *
     * 设计说明：
     * - 记录筛选：先用 poolType 限定池（null=全池），再 rarity，再 searchQuery 模糊匹配 itemName。
     *   全流程在内存中用 `List.filter` 完成，避免在 DAO 里新增 7 个对称的非分页查询方法。
     * - 五星间隔：遵循"角色池 301+400 共享保底 → 合并后计算；其他池单独计算"的规则，
     *   与 GachaStatsCalculator / HomeViewModel / StatsViewModel 保持一致。
     * - 垫抽 currentPity：
     *   · 若用户指定了池（filter.poolType != null），仅统计该池 / 共享池组合的垫抽。
     *   · 若为"全部池"，取各池垫抽中的最大值（用户最关心的"最接近保底"的那个）。
     */
    private suspend fun computeStats(accountId: Long, filter: HistoryFilter) {
        // 1. 从 DB 拿原始分池记录（与 Paging 底层同源 = 同一 accountId + 同池）
        val characterRecords = gachaRepository.getRecordsByPool(accountId, GachaType.CHARACTER.value)
        val character2Records = gachaRepository.getRecordsByPool(accountId, GachaType.CHARACTER_2.value)
        val weaponRecords = gachaRepository.getRecordsByPool(accountId, GachaType.WEAPON.value)
        val standardRecords = gachaRepository.getRecordsByPool(accountId, GachaType.STANDARD.value)
        val noviceRecords = gachaRepository.getRecordsByPool(accountId, GachaType.NOVICE.value)
        val chronicledRecords = gachaRepository.getRecordsByPool(accountId, GachaType.CHRONICLED.value)

        // 2. 按 rarity + searchQuery 在内存中过滤（每池独立过滤，保留池身份给间隔/垫抽用）
        val predicate: (GachaRecordEntity) -> Boolean = { r ->
            (filter.rarity == null || r.rarity == filter.rarity) &&
                (filter.searchQuery.isBlank() || r.itemName.contains(filter.searchQuery, ignoreCase = true))
        }
        val char = characterRecords.filter(predicate)
        val char2 = character2Records.filter(predicate)
        val weapon = weaponRecords.filter(predicate)
        val standard = standardRecords.filter(predicate)
        val novice = noviceRecords.filter(predicate)
        val chronicled = chronicledRecords.filter(predicate)

        // 3. 按 poolType 切片。allFiltered = 真正参与 summary 统计的记录集合
        val allFiltered: List<GachaRecordEntity>

        when (filter.poolType) {
            null -> { // 全部池
                allFiltered = char + char2 + weapon + standard + novice + chronicled
            }
            GachaType.CHARACTER.value -> {
                allFiltered = char
            }
            GachaType.CHARACTER_2.value -> {
                allFiltered = char2
            }
            GachaType.WEAPON.value -> {
                allFiltered = weapon
            }
            GachaType.STANDARD.value -> {
                allFiltered = standard
            }
            GachaType.NOVICE.value -> {
                allFiltered = novice
            }
            GachaType.CHRONICLED.value -> {
                allFiltered = chronicled
            }
            else -> { // 不识别的池值 → 回退为空，避免脏数据
                allFiltered = emptyList()
            }
        }

        // 4. 五星出金间隔：使用未过滤的原始记录（保留完整池历史），
        //    按 poolType 限定参与计算的池，按保底共享池维度各自计算
        //    传入各池保底上限，过滤数据不完整导致的不可能间隔（> 90/80）
        val intervals = mutableMapOf<String, Int>()
        when (filter.poolType) {
            null -> {
                computeFiveStarIntervals(characterRecords + character2Records, intervals, statsCalculator.getPityCeiling(GachaType.CHARACTER.value))
                computeFiveStarIntervals(weaponRecords, intervals, statsCalculator.getPityCeiling(GachaType.WEAPON.value))
                computeFiveStarIntervals(standardRecords, intervals, statsCalculator.getPityCeiling(GachaType.STANDARD.value))
                computeFiveStarIntervals(noviceRecords, intervals, statsCalculator.getPityCeiling(GachaType.NOVICE.value))
                computeFiveStarIntervals(chronicledRecords, intervals, statsCalculator.getPityCeiling(GachaType.CHRONICLED.value))
            }
            GachaType.CHARACTER.value, GachaType.CHARACTER_2.value -> {
                computeFiveStarIntervals(characterRecords + character2Records, intervals, statsCalculator.getPityCeiling(GachaType.CHARACTER.value))
            }
            GachaType.WEAPON.value -> {
                computeFiveStarIntervals(weaponRecords, intervals, statsCalculator.getPityCeiling(GachaType.WEAPON.value))
            }
            GachaType.STANDARD.value -> {
                computeFiveStarIntervals(standardRecords, intervals, statsCalculator.getPityCeiling(GachaType.STANDARD.value))
            }
            GachaType.NOVICE.value -> {
                computeFiveStarIntervals(noviceRecords, intervals, statsCalculator.getPityCeiling(GachaType.NOVICE.value))
            }
            GachaType.CHRONICLED.value -> {
                computeFiveStarIntervals(chronicledRecords, intervals, statsCalculator.getPityCeiling(GachaType.CHRONICLED.value))
            }
            else -> {}
        }
        _fiveStarIntervals.value = intervals

        // 5. 垫抽：使用未过滤的原始记录计算，选"全部池"时取最大值
        val pityScopes: List<List<GachaRecordEntity>> = when (filter.poolType) {
            null -> listOf(
                characterRecords + character2Records,
                weaponRecords, standardRecords, noviceRecords, chronicledRecords
            )
            GachaType.CHARACTER.value, GachaType.CHARACTER_2.value ->
                listOf(characterRecords + character2Records)
            GachaType.WEAPON.value -> listOf(weaponRecords)
            GachaType.STANDARD.value -> listOf(standardRecords)
            GachaType.NOVICE.value -> listOf(noviceRecords)
            GachaType.CHRONICLED.value -> listOf(chronicledRecords)
            else -> emptyList()
        }
        val pityValues = pityScopes
            .map { statsCalculator.calculateCurrentPity(it) }
            .filter { it > 0 }
        val pity = if (filter.poolType == null) pityValues.maxOrNull() ?: 0
                   else pityValues.firstOrNull() ?: 0

        _summary.value = HistorySummary(
            totalPulls = allFiltered.size,
            fiveStarCount = allFiltered.count { it.rarity == 5 },
            fourStarCount = allFiltered.count { it.rarity == 4 },
            currentPity = pity
        )

        // 6. 每日统计（按 date 分组）—— 同样基于过滤后的记录，保证和列表日期 header 对应
        _dailyStats.value = allFiltered
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
