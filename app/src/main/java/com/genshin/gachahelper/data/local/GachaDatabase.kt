package com.genshin.gachahelper.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.genshin.gachahelper.data.local.dao.AccountDao
import com.genshin.gachahelper.data.local.dao.GachaRecordDao
import com.genshin.gachahelper.data.local.dao.PoolDao
import com.genshin.gachahelper.data.local.entity.AccountEntity
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.local.entity.PoolEntity

@Database(
    entities = [
        AccountEntity::class,
        GachaRecordEntity::class,
        PoolEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class GachaDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun gachaRecordDao(): GachaRecordDao
    abstract fun poolDao(): PoolDao

    companion object {
        const val DATABASE_NAME = "gacha_db"
    }
}
