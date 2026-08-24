package com.genshin.gachahelper.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.genshin.gachahelper.auth.AuthRepository
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
import javax.inject.Inject

data class HistoryFilter(
    val poolType: Int? = null, // null = 全部
    val rarity: Int? = null    // null = 全部
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val gachaRepository: GachaRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    val records: Flow<PagingData<GachaRecordEntity>> = _filter
        .flatMapLatest { filter ->
            val uid = runCatching { authRepository.getUid() }.getOrNull()
            val account = runCatching { gachaRepository.getAccountByUid(uid ?: "") }.getOrNull()

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
