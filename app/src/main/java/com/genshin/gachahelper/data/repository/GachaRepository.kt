package com.genshin.gachahelper.data.repository

import com.genshin.gachahelper.data.local.dao.AccountDao
import com.genshin.gachahelper.data.local.dao.GachaRecordDao
import com.genshin.gachahelper.data.local.dao.PoolDao
import com.genshin.gachahelper.data.local.entity.AccountEntity
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.local.entity.PoolEntity
import com.genshin.gachahelper.data.local.dao.GachaRecordDao.RecordKey
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GachaRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val gachaRecordDao: GachaRecordDao,
    private val poolDao: PoolDao
) {
    // Account operations
    fun getCurrentAccount(): Flow<AccountEntity?> = accountDao.getCurrentAccount()

    suspend fun getAccountByUid(uid: String): AccountEntity? =
        accountDao.getAccountByUid(uid)

    suspend fun insertAccount(account: AccountEntity): Long =
        accountDao.insert(account)

    suspend fun updateLastSyncTime(accountId: Long, syncTime: Long) =
        accountDao.updateLastSyncTime(accountId, syncTime)

    suspend fun deleteAccount(accountId: Long) =
        accountDao.deleteAccount(accountId)

    // GachaRecord operations
    suspend fun insertRecords(records: List<GachaRecordEntity>): Int {
        val results = gachaRecordDao.insertAll(records)
        return results.count { it != -1L }
    }

    suspend fun getMaxOrderNumber(accountId: Long, poolType: Int): String? =
        gachaRecordDao.getMaxOrderNumber(accountId, poolType)

    suspend fun getRecordCount(accountId: Long, poolType: Int): Int =
        gachaRecordDao.getRecordCount(accountId, poolType)

    suspend fun getRarityCount(accountId: Long, poolType: Int, rarity: Int): Int =
        gachaRecordDao.getRarityCount(accountId, poolType, rarity)

    suspend fun getRecordsByPool(accountId: Long, poolType: Int): List<GachaRecordEntity> =
        gachaRecordDao.getRecordsByPool(accountId, poolType)

    fun getAllRecordsPaged(accountId: Long) =
        gachaRecordDao.getAllRecordsPaged(accountId)

    fun getRecordsPagedByPool(accountId: Long, poolType: Int) =
        gachaRecordDao.getRecordsPagedByPool(accountId, poolType)

    fun getRecordsPagedByRarity(accountId: Long, rarity: Int) =
        gachaRecordDao.getRecordsPagedByRarity(accountId, rarity)

    fun getRecordsPagedByPoolAndRarity(accountId: Long, poolType: Int, rarity: Int) =
        gachaRecordDao.getRecordsPagedByPoolAndRarity(accountId, poolType, rarity)

    suspend fun deleteAllByAccount(accountId: Long) =
        gachaRecordDao.deleteAllByAccount(accountId)

    suspend fun getLatestOrderNumber(accountId: Long, poolType: Int): String? =
        gachaRecordDao.getLatestOrderNumber(accountId, poolType)

    suspend fun getRecordKeysByAccount(accountId: Long): List<RecordKey> =
        gachaRecordDao.getRecordKeysByAccount(accountId)

    // Pool operations
    suspend fun insertAllPools(pools: List<PoolEntity>) =
        poolDao.insertAll(pools)

    suspend fun getPoolsByType(type: Int): List<PoolEntity> =
        poolDao.getPoolsByType(type)

    suspend fun getAllPools(): List<PoolEntity> =
        poolDao.getAllPools()
}
