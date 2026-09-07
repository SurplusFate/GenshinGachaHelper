package com.genshin.gachahelper.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 卡池表
 */
@Entity(tableName = "pool")
data class PoolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: Int,
    val startTime: String? = null,
    val endTime: String? = null
)
