package com.genshin.gachahelper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.genshin.gachahelper.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Query("SELECT * FROM account ORDER BY createTime DESC LIMIT 1")
    fun getCurrentAccount(): Flow<AccountEntity?>

    @Query("SELECT * FROM account WHERE uid = :uid")
    suspend fun getAccountByUid(uid: String): AccountEntity?

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun getAccountById(id: Long): AccountEntity?

    @Query("UPDATE account SET lastSyncTime = :syncTime WHERE id = :accountId")
    suspend fun updateLastSyncTime(accountId: Long, syncTime: Long)

    @Query("DELETE FROM account WHERE id = :accountId")
    suspend fun deleteAccount(accountId: Long)
}
