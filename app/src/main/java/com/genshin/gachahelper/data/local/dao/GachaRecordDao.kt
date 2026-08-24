package com.genshin.gachahelper.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity

@Dao
interface GachaRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: GachaRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<GachaRecordEntity>): List<Long>

    @Query("SELECT orderNumber FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType ORDER BY CAST(orderNumber AS INTEGER) DESC LIMIT 1")
    suspend fun getMaxOrderNumber(accountId: Long, poolType: Int): String?

    @Query("SELECT COUNT(*) FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType")
    suspend fun getRecordCount(accountId: Long, poolType: Int): Int

    @Query("SELECT COUNT(*) FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType AND rarity = :rarity")
    suspend fun getRarityCount(accountId: Long, poolType: Int, rarity: Int): Int

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType ORDER BY CAST(orderNumber AS INTEGER) DESC")
    suspend fun getRecordsByPool(accountId: Long, poolType: Int): List<GachaRecordEntity>

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId ORDER BY CAST(orderNumber AS INTEGER) DESC")
    fun getAllRecordsPaged(accountId: Long): PagingSource<Int, GachaRecordEntity>

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType ORDER BY CAST(orderNumber AS INTEGER) DESC")
    fun getRecordsPagedByPool(accountId: Long, poolType: Int): PagingSource<Int, GachaRecordEntity>

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND rarity = :rarity ORDER BY CAST(orderNumber AS INTEGER) DESC")
    fun getRecordsPagedByRarity(accountId: Long, rarity: Int): PagingSource<Int, GachaRecordEntity>

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType AND rarity = :rarity ORDER BY CAST(orderNumber AS INTEGER) DESC")
    fun getRecordsPagedByPoolAndRarity(
        accountId: Long,
        poolType: Int,
        rarity: Int
    ): PagingSource<Int, GachaRecordEntity>

    @Query("DELETE FROM gacha_record WHERE accountId = :accountId")
    suspend fun deleteAllByAccount(accountId: Long)

    @Query("SELECT orderNumber FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType ORDER BY orderNumber DESC LIMIT 1")
    suspend fun getLatestOrderNumber(accountId: Long, poolType: Int): String?

    /**
     * 获取指定账号下所有记录的内容指纹（poolType, time, itemName）
     * 用于导入时的二级去重：当 ID 不匹配但内容相同时也跳过
     */
    @Query("SELECT poolType, time, itemName FROM gacha_record WHERE accountId = :accountId")
    suspend fun getRecordKeysByAccount(accountId: Long): List<RecordKey>

    /**
     * 用于内容去重的数据类
     */
    data class RecordKey(
        val poolType: Int,
        val time: String,
        val itemName: String
    )
}
