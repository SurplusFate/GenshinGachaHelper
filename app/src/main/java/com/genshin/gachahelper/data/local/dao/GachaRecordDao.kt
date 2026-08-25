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

    // ===== 搜索查询 =====

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND itemName LIKE '%' || :query || '%' ORDER BY CAST(orderNumber AS INTEGER) DESC")
    fun getRecordsPagedBySearch(accountId: Long, query: String): PagingSource<Int, GachaRecordEntity>

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType AND itemName LIKE '%' || :query || '%' ORDER BY CAST(orderNumber AS INTEGER) DESC")
    fun getRecordsPagedByPoolAndSearch(accountId: Long, poolType: Int, query: String): PagingSource<Int, GachaRecordEntity>

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND rarity = :rarity AND itemName LIKE '%' || :query || '%' ORDER BY CAST(orderNumber AS INTEGER) DESC")
    fun getRecordsPagedByRarityAndSearch(accountId: Long, rarity: Int, query: String): PagingSource<Int, GachaRecordEntity>

    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND poolType = :poolType AND rarity = :rarity AND itemName LIKE '%' || :query || '%' ORDER BY CAST(orderNumber AS INTEGER) DESC")
    fun getRecordsPagedByPoolAndRarityAndSearch(accountId: Long, poolType: Int, rarity: Int, query: String): PagingSource<Int, GachaRecordEntity>

    // ===== 聚合查询（统计页用） =====

    /** 所有五星记录（历史页计算间隔 + 统计页时间轴用） */
    @Query("SELECT * FROM gacha_record WHERE accountId = :accountId AND rarity = 5 ORDER BY CAST(orderNumber AS INTEGER) DESC")
    suspend fun getAllFiveStars(accountId: Long): List<GachaRecordEntity>

    /** 按物品名聚合统计（图鉴 Tab 用） */
    @Query("""
        SELECT itemName, rarity, COUNT(*) as count, poolType
        FROM gacha_record
        WHERE accountId = :accountId
        GROUP BY itemName, rarity
        ORDER BY rarity DESC, count DESC
    """)
    suspend fun getItemCollection(accountId: Long): List<ItemCount>

    /** 按天聚合统计（日历热力图用） */
    @Query("""
        SELECT substr(time, 1, 10) as date,
               COUNT(*) as count,
               SUM(CASE WHEN rarity = 5 THEN 1 ELSE 0 END) as fiveCount
        FROM gacha_record
        WHERE accountId = :accountId
        GROUP BY date
        ORDER BY date DESC
    """)
    suspend fun getDailyStats(accountId: Long): List<DailyStat>

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

    // ===== 聚合数据类 =====

    data class ItemCount(
        val itemName: String,
        val rarity: Int,
        val count: Int,
        val poolType: Int
    )

    data class DailyStat(
        val date: String,
        val count: Int,
        val fiveCount: Int
    )

    /**
     * 用于内容去重的数据类
     */
    data class RecordKey(
        val poolType: Int,
        val time: String,
        val itemName: String
    )
}
