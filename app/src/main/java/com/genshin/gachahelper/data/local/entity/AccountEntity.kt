package com.genshin.gachahelper.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户账号表
 */
@Entity(
    tableName = "account",
    indices = [Index(value = ["uid"], unique = true)]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val server: String,
    val nickname: String? = null,
    val createTime: Long,
    val lastSyncTime: Long? = null
)
