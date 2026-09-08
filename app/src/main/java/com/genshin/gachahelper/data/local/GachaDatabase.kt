package com.genshin.gachahelper.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false
)
abstract class GachaDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun gachaRecordDao(): GachaRecordDao
    abstract fun poolDao(): PoolDao

    companion object {
        const val DATABASE_NAME = "gacha_db"

        /**
         * v1 → v2 迁移：清除角色池（301/400）记录
         *
         * 旧版本解析器不读取响应中的 gacha_type 字段，用请求参数作为 poolType，
         * 导致 400 池记录被错误标记为 301。由于唯一约束是 accountId+orderNumber，
         * 已存为 301 的记录无法被更正为 400。迁移清除所有 301/400 记录，
         * 下次同步时解析器会正确分类。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM gacha_record WHERE poolType IN (301, 400)")
            }
        }
    }
}
