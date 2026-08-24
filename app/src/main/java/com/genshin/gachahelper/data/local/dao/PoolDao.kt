package com.genshin.gachahelper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.genshin.gachahelper.data.local.entity.PoolEntity

@Dao
interface PoolDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pool: PoolEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pools: List<PoolEntity>)

    @Query("SELECT * FROM pool WHERE type = :type ORDER BY startTime DESC")
    suspend fun getPoolsByType(type: Int): List<PoolEntity>

    @Query("SELECT * FROM pool ORDER BY startTime DESC")
    suspend fun getAllPools(): List<PoolEntity>
}
