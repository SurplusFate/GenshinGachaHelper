package com.genshin.gachahelper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.auth.AuthRepository
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
import javax.inject.Inject

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val uid: String? = null,
    val nickname: String? = null,
    val characterStats: PoolStats? = null,
    val weaponStats: PoolStats? = null,
    val standardStats: PoolStats? = null,
    val syncState: SyncState = SyncState.Idle,
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val gachaRepository: GachaRepository,
    private val statsCalculator: GachaStatsCalculator,
    private val syncService: GachaSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            syncService.syncState.collect { syncState ->
                _uiState.value = _uiState.value.copy(syncState = syncState)
                if (syncState is SyncState.Success) {
                    loadStats()
                }
            }
        }
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
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

    private suspend fun loadStats() {
        val account = gachaRepository.getCurrentAccount().first()
        if (account == null) {
            _uiState.value = _uiState.value.copy(isLoading = false)
            return
        }

        val accountId = account.id

        // 加载各卡池记录并计算统计
        val characterRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.CHARACTER.value
        )
        val weaponRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.WEAPON.value
        )
        val standardRecords = gachaRepository.getRecordsByPool(
            accountId, GachaType.STANDARD.value
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

        _uiState.value = _uiState.value.copy(
            characterStats = characterStats,
            weaponStats = weaponStats,
            standardStats = standardStats,
            isLoading = false
        )
    }

    fun sync() {
        viewModelScope.launch {
            syncService.syncAll()
        }
    }
}
