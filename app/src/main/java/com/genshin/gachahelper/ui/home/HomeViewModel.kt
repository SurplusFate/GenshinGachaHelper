package com.genshin.gachahelper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.sync.GachaSyncService
import com.genshin.gachahelper.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val uid: String? = null,
    val nickname: String? = null,
    val characterStats: PoolStats? = null,
    val weaponStats: PoolStats? = null,
    val standardStats: PoolStats? = null,
    val chronicledStats: PoolStats? = null,
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

                _uiState.value = _uiState.value.copy(
                    isLoggedIn = loggedIn,
                    uid = uid,
                    nickname = nickname
                )

                if (loggedIn) {
                    loadStats()
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    private suspend fun loadStats() {
        val account = gachaRepository.getCurrentAccount().first()
        if (account == null) {
            _uiState.value = _uiState.value.copy(isLoading = false)
            return
        }

        val accountId = account.id

        // 加载各卡池记录并计算统计（包含集录祈愿）
        val characterRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.CHARACTER.value
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

        val characterStats = if (characterRecords.isNotEmpty()) {
            statsCalculator.calculatePoolStats(characterRecords, GachaType.CHARACTER.value)
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

        _uiState.value = _uiState.value.copy(
            characterStats = characterStats,
            weaponStats = weaponStats,
            standardStats = standardStats,
            chronicledStats = chronicledStats,
            isLoading = false
        )
    }

    fun sync() {
        viewModelScope.launch {
            syncService.syncAll()
        }
    }
}
