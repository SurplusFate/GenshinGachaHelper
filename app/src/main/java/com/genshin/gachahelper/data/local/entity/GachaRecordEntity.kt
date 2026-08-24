package com.genshin.gachahelper.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 抽卡记录表
 * 联合唯一键：account_id + order_number，用于增量去重
 */
@Entity(
    tableName = "gacha_record",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId", "orderNumber"], unique = true),
        Index(value = ["accountId", "poolType"]),
        Index(value = ["time"])
    ]
)
data class GachaRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val poolType: Int,
    val itemName: String,
    val itemType: Int,
    val rarity: Int,
    val time: String,
    val orderNumber: String
)
