package com.genshin.gachahelper.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.analysis.GachaReport
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val statsCalculator: GachaStatsCalculator
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val uid = authRepository.getUid()
            val account = gachaRepository.getAccountByUid(uid ?: "")

            if (account == null) {
                _uiState.value = StatsUiState(isLoading = false, hasData = false)
                return@launch
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
