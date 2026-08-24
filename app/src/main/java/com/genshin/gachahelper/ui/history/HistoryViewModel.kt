package com.genshin.gachahelper.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryFilter(
    val poolType: Int? = null, // null = 全部
    val rarity: Int? = null    // null = 全部
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val gachaRepository: GachaRepository,
    private val authRepository: AuthRepository,
    private val sessionEventBus: SessionEventBus
) : ViewModel() {

    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    // 刷新触发器：每次收到事件就自增，使 flatMapLatest 重新创建 Pager
    private val _refreshTrigger = MutableStateFlow(0)

    val records: Flow<PagingData<GachaRecordEntity>> = _refreshTrigger
        .flatMapLatest { _ ->
            _filter.flatMapLatest { filter ->
                val uid = runCatching { authRepository.getUid() }.getOrNull()
                val account = runCatching { gachaRepository.getActiveAccount(uid) }.getOrNull()

                if (account == null) {
                    flowOf(PagingData.empty())
                } else {
                    val accountId = account.id
                    val pagingSourceFactory = when {
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
        // 监听全局会话事件，收到后触发 Pager 重建
        viewModelScope.launch {
            sessionEventBus.events.collect { event ->
                when (event) {
                    SessionEvent.LoginCompleted,
                    SessionEvent.DataImported,
                    SessionEvent.DataSynced -> _refreshTrigger.value++

                    SessionEvent.LogoutCompleted,
                    SessionEvent.DataCleared -> {
                        _filter.value = HistoryFilter()
                        _refreshTrigger.value++
                    }
                }
            }
        }
    }

    fun setPoolFilter(poolType: Int?) {
        _filter.value = _filter.value.copy(poolType = poolType)
    }

    fun setRarityFilter(rarity: Int?) {
        _filter.value = _filter.value.copy(rarity = rarity)
    }

    fun getPoolTypeName(poolType: Int): String {
        return GachaType.fromValue(poolType).displayName
    }
}
