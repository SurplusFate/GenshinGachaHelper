package com.genshin.gachahelper.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.analysis.GachaReport
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class StatsUiState(
    val report: GachaReport? = null,
    val isLoading: Boolean = true,
    val hasData: Boolean = false
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
                val account = gachaRepository.getAccountByUid(uid ?: "")

                if (account == null) {
                    _uiState.value = StatsUiState(isLoading = false, hasData = false)
                    return@withLock
                }

                val characterRecords = gachaRepository.getRecordsByPool(
                    account.id, GachaType.CHARACTER.value
                )
                val weaponRecords = gachaRepository.getRecordsByPool(
                    account.id, GachaType.WEAPON.value
                )
                val standardRecords = gachaRepository.getRecordsByPool(
                    account.id, GachaType.STANDARD.value
                )
                val chronicledRecords = gachaRepository.getRecordsByPool(
                    account.id, GachaType.CHRONICLED.value
                )

                val report = statsCalculator.generateReport(
                    characterRecords = characterRecords,
                    weaponRecords = weaponRecords,
                    standardRecords = standardRecords,
                    chronicledRecords = chronicledRecords
                )

                _uiState.value = StatsUiState(
                    report = report,
                    isLoading = false,
                    hasData = report.totalPulls > 0
                )
            }
        }
    }
}
