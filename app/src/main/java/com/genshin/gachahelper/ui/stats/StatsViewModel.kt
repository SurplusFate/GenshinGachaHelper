package com.genshin.gachahelper.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.analysis.GachaReport
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.local.dao.GachaRecordDao.DailyStat
import com.genshin.gachahelper.data.local.dao.GachaRecordDao.ItemCount
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 时间轴单条五星出金记录
 *
 * @param itemName    角色名/武器名
 * @param time        出金时间（原始字符串，形如 "yyyy-MM-dd HH:mm:ss"）
 * @param interval    距上一条五星的间隔抽数（首条为从池子起始算起的抽数）
 * @param poolName    所属卡池名
 * @param orderNumber 抽卡序号（用于正确排序，time 只有秒级精度不可靠）
 */
data class FiveStarTimelineItem(
    val itemName: String,
    val time: String,
    val interval: Int,
    val poolName: String,
    val orderNumber: String
)

data class StatsUiState(
    val report: GachaReport? = null,
    val isLoading: Boolean = true,
    val hasData: Boolean = false,
    val fiveStarTimeline: List<FiveStarTimelineItem> = emptyList(),
    val itemCollection: List<ItemCount> = emptyList(),
    val dailyStats: List<DailyStat> = emptyList()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val gachaRepository: GachaRepository,
    private val authRepository: AuthRepository,
    private val statsCalculator: GachaStatsCalculator,
    private val sessionEventBus: SessionEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    // 串行化 loadStats，避免事件并发触发时多次重叠写 _uiState 造成 last-write-wins 回退
    private val loadMutex = Mutex()

    init {
        // 监听全局会话事件（登录/导入/同步/清除等），由事件总线统一驱动刷新
        viewModelScope.launch {
            sessionEventBus.events.collect { event ->
                when (event) {
                    SessionEvent.LoginCompleted,
                    SessionEvent.DataImported,
                    SessionEvent.DataSynced -> loadStats()

                    SessionEvent.LogoutCompleted,
                    SessionEvent.DataCleared -> {
                        _uiState.value = StatsUiState(isLoading = false, hasData = false)
                    }
                }
            }
        }

        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            loadMutex.withLock {
                _uiState.value = _uiState.value.copy(isLoading = true)

                val uid = authRepository.getUid()
                val account = gachaRepository.getActiveAccount(uid)

                if (account == null) {
                    _uiState.value = StatsUiState(isLoading = false, hasData = false)
                    return@withLock
                }

                // DB 拉取 + 全量统计计算放到后台线程，避免阻塞主线程导致页面卡顿。
                val loaded = withContext(Dispatchers.Default) {
                    val characterRecords = gachaRepository.getRecordsByPool(
                        account.id, GachaType.CHARACTER.value
                    )
                    val character2Records = gachaRepository.getRecordsByPool(
                        account.id, GachaType.CHARACTER_2.value
                    )
                    val weaponRecords = gachaRepository.getRecordsByPool(
                        account.id, GachaType.WEAPON.value
                    )
                    val standardRecords = gachaRepository.getRecordsByPool(
                        account.id, GachaType.STANDARD.value
                    )
                    val noviceRecords = gachaRepository.getRecordsByPool(
                        account.id, GachaType.NOVICE.value
                    )
                    val chronicledRecords = gachaRepository.getRecordsByPool(
                        account.id, GachaType.CHRONICLED.value
                    )

                    val report = statsCalculator.generateReport(
                        characterRecords = characterRecords,
                        character2Records = character2Records,
                        weaponRecords = weaponRecords,
                        standardRecords = standardRecords,
                        noviceRecords = noviceRecords,
                        chronicledRecords = chronicledRecords
                    )

                    // 额外加载时间轴 / 图鉴 / 日历三组数据
                    val fiveStarTimeline = buildFiveStarTimeline(
                        characterRecords = characterRecords,
                        character2Records = character2Records,
                        weaponRecords = weaponRecords,
                        standardRecords = standardRecords,
                        noviceRecords = noviceRecords,
                        chronicledRecords = chronicledRecords
                    )
                    val itemCollection = gachaRepository.getItemCollection(account.id)
                    val dailyStats = gachaRepository.getDailyStats(account.id)

                    StatsUiState(
                        report = report,
                        isLoading = false,
                        hasData = report.totalPulls > 0,
                        fiveStarTimeline = fiveStarTimeline,
                        itemCollection = itemCollection,
                        dailyStats = dailyStats
                    )
                }

                _uiState.value = loaded
            }
        }
    }

    /**
     * 构建五星出金时间轴。
     *
     * 角色池 301 和 400 共享保底，合并后计算间隔，避免出现超过保底上限的不合理间隔。
     * 最终按 orderNumber 正序（从早到晚）排列，便于时间轴从左到右展示。
     * 间隔计算委托给 GachaStatsCalculator，保证与统计页结果一致。
     */
    private fun buildFiveStarTimeline(
        characterRecords: List<GachaRecordEntity>,
        character2Records: List<GachaRecordEntity>,
        weaponRecords: List<GachaRecordEntity>,
        standardRecords: List<GachaRecordEntity>,
        noviceRecords: List<GachaRecordEntity>,
        chronicledRecords: List<GachaRecordEntity>
    ): List<FiveStarTimelineItem> {
        val timeline = mutableListOf<FiveStarTimelineItem>()
        // 角色池301和400共享保底，合并后计算间隔
        addPoolTimeline(timeline, characterRecords + character2Records, "角色池")
        addPoolTimeline(timeline, weaponRecords, "武器池")
        addPoolTimeline(timeline, standardRecords, "常驻池")
        addPoolTimeline(timeline, noviceRecords, "新手池")
        addPoolTimeline(timeline, chronicledRecords, "集录池")
        // 按 orderNumber 正序排列（从早到晚）
        return timeline.sortedBy { it.orderNumber }
    }

    /**
     * 计算单个（或合并）卡池的五星间隔，结果追加到 [timeline]。
     * 使用 GachaStatsCalculator 计算间隔列表，保证计算口径统一。
     */
    private fun addPoolTimeline(
        timeline: MutableList<FiveStarTimelineItem>,
        records: List<GachaRecordEntity>,
        poolName: String
    ) {
        if (records.isEmpty()) return
        // 用计算器获取间隔列表（内部已按 orderNumber 排序）
        val intervals = statsCalculator.calculateFiveStarIntervals(records)
        // 获取按 orderNumber 升序排列的五星记录
        val fiveStars = records
            .filter { it.rarity == 5 }
            .sortedBy { it.orderNumber }
        // 一一对应
        for (i in fiveStars.indices) {
            if (i < intervals.size) {
                timeline.add(
                    FiveStarTimelineItem(
                        itemName = fiveStars[i].itemName,
                        time = fiveStars[i].time,
                        interval = intervals[i],
                        poolName = poolName,
                        orderNumber = fiveStars[i].orderNumber
                    )
                )
            }
        }
    }
}
