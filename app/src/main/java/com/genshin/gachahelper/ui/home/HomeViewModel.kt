package com.genshin.gachahelper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.sync.GachaSyncService
import com.genshin.gachahelper.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val uid: String? = null,
    val nickname: String? = null,
    val hasData: Boolean = false,
    val characterStats: PoolStats? = null,
    val character2Stats: PoolStats? = null,
    val weaponStats: PoolStats? = null,
    val standardStats: PoolStats? = null,
    val noviceStats: PoolStats? = null,
    val chronicledStats: PoolStats? = null,
    val recentFiveStars: List<GachaRecordEntity> = emptyList(),
    val recentFiveStarIntervals: List<Int> = emptyList(),
    val syncState: SyncState = SyncState.Idle,
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val gachaRepository: GachaRepository,
    private val statsCalculator: GachaStatsCalculator,
    private val syncService: GachaSyncService,
    private val sessionEventBus: SessionEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // 串行化 loadData，避免事件并发触发时多个加载重叠写 _uiState 造成 last-write-wins 回退
    private val loadMutex = Mutex()

    init {
        // 监听同步状态：仅用于 UI 显示同步进度，刷新由 SessionEventBus.DataSynced 驱动
        viewModelScope.launch {
            syncService.syncState.collect { syncState ->
                _uiState.value = _uiState.value.copy(syncState = syncState)
            }
        }

        // 监听全局会话事件（登录/导入/同步/清除等）
        viewModelScope.launch {
            sessionEventBus.events.collect { event ->
                when (event) {
                    SessionEvent.LoginCompleted,
                    SessionEvent.DataImported,
                    SessionEvent.DataSynced -> loadData()

                    SessionEvent.LogoutCompleted,
                    SessionEvent.DataCleared -> {
                        _uiState.value = HomeUiState(isLoading = false)
                    }
                }
            }
        }

        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            loadMutex.withLock {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val loggedIn = authRepository.isLoggedIn()
                val uid = authRepository.getUid()
                val nickname = authRepository.getNickname()

                // 通过活跃账号解析：登录时用登录 UID，未登录时回退到最近导入的账号
                val account = gachaRepository.getActiveAccount(uid)

                _uiState.value = _uiState.value.copy(
                    isLoggedIn = loggedIn,
                    uid = account?.uid,
                    nickname = nickname
                )

                if (account != null) {
                    loadStats(account.id)
                } else {
                    _uiState.value = _uiState.value.copy(
                        hasData = false,
                        isLoading = false
                    )
                }
            }
        }
    }

    private suspend fun loadStats(accountId: Long) {
        // 加载各卡池记录并计算统计（包含角色活动祈愿-2 和集录祈愿）
        val characterRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.CHARACTER.value
        )
        val character2Records = gachaRepository.getRecordsByPool(
            accountId, GachaType.CHARACTER_2.value
        )
        val weaponRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.WEAPON.value
        )
        val standardRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.STANDARD.value
        )
        val chronicledRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.CHRONICLED.value
        )
        val noviceRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.NOVICE.value
        )

        val characterStats = if (characterRecords.isNotEmpty()) {
            statsCalculator.calculatePoolStats(
                characterRecords, GachaType.CHARACTER.value,
                sharedPityRecords = character2Records
            )
        } else null

        val character2Stats = if (character2Records.isNotEmpty()) {
            statsCalculator.calculatePoolStats(
                character2Records, GachaType.CHARACTER_2.value,
                sharedPityRecords = characterRecords
            )
        } else null

        val weaponStats = if (weaponRecords.isNotEmpty()) {
            statsCalculator.calculatePoolStats(weaponRecords, GachaType.WEAPON.value)
        } else null

        val standardStats = if (standardRecords.isNotEmpty()) {
            statsCalculator.calculatePoolStats(standardRecords, GachaType.STANDARD.value)
        } else null

        val chronicledStats = if (chronicledRecords.isNotEmpty()) {
            statsCalculator.calculatePoolStats(chronicledRecords, GachaType.CHRONICLED.value)
        } else null

        val noviceStats = if (noviceRecords.isNotEmpty()) {
            statsCalculator.calculatePoolStats(noviceRecords, GachaType.NOVICE.value)
        } else null

        val hasAnyData = characterStats != null || character2Stats != null ||
                weaponStats != null || standardStats != null ||
                noviceStats != null || chronicledStats != null

        // 加载最近五星记录（最多 10 条），并计算每条五星距上一个五星的出金间隔
        val poolRecords = mapOf(
            GachaType.CHARACTER.value to characterRecords,
            GachaType.CHARACTER_2.value to character2Records,
            GachaType.WEAPON.value to weaponRecords,
            GachaType.STANDARD.value to standardRecords,
            GachaType.NOVICE.value to noviceRecords,
            GachaType.CHRONICLED.value to chronicledRecords
        )
        val recentWithIntervals = loadRecentFiveStars(accountId, poolRecords)

        _uiState.value = _uiState.value.copy(
            characterStats = characterStats,
            character2Stats = character2Stats,
            weaponStats = weaponStats,
            standardStats = standardStats,
            noviceStats = noviceStats,
            chronicledStats = chronicledStats,
            recentFiveStars = recentWithIntervals.map { it.first },
            recentFiveStarIntervals = recentWithIntervals.map { it.second },
            hasData = hasAnyData,
            isLoading = false
        )
    }

    /**
     * 加载最近五星记录（最多 10 条），并计算每条五星的出金间隔。
     *
     * 间隔计算与 GachaStatsCalculator 保持一致：
     * 角色池 301+400 共享保底，合并后按 time 正序计算间隔；其他池单独算。
     */
    private suspend fun loadRecentFiveStars(
        accountId: Long,
        poolRecords: Map<Int, List<GachaRecordEntity>>
    ): List<Pair<GachaRecordEntity, Int>> {
        val allFiveStars = gachaRepository.getAllFiveStars(accountId)
        val recent = allFiveStars.take(10)
        if (recent.isEmpty()) return emptyList()

        // 五星记录 id -> 出金间隔（距上一个五星多少抽）
        val intervalById = mutableMapOf<Long, Int>()

        // 角色池 301+400 合并计算
        val charRecords = (poolRecords[GachaType.CHARACTER.value].orEmpty() +
            poolRecords[GachaType.CHARACTER_2.value].orEmpty())
        computeIntervals(charRecords, intervalById)

        // 其他池单独计算
        for ((type, records) in poolRecords) {
            if (type == GachaType.CHARACTER.value || type == GachaType.CHARACTER_2.value) continue
            computeIntervals(records, intervalById)
        }

        return recent.map { fiveStar ->
            fiveStar to (intervalById[fiveStar.id] ?: 0)
        }
    }

    /**
     * 计算一组记录中的五星出金间隔，结果写入 [result] Map。
     * 按 time 正序排列，每遇到五星计算距上一条五星的位置差。
     */
    private fun computeIntervals(
        records: List<GachaRecordEntity>,
        result: MutableMap<Long, Int>
    ) {
        if (records.isEmpty()) return
        val sorted = records.sortedBy { it.time }
        var lastFiveStarIndex = -1
        for ((index, record) in sorted.withIndex()) {
            if (record.rarity == 5) {
                val interval = if (lastFiveStarIndex == -1) index + 1
                else index - lastFiveStarIndex
                result[record.id] = interval
                lastFiveStarIndex = index
            }
        }
    }

    fun sync() {
        viewModelScope.launch {
            syncService.syncAll()
        }
    }
}
