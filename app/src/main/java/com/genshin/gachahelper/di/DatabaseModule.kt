package com.genshin.gachahelper.di

import android.content.Context
import androidx.room.Room
import com.genshin.gachahelper.data.local.GachaDatabase
import com.genshin.gachahelper.data.local.dao.AccountDao
import com.genshin.gachahelper.data.local.dao.GachaRecordDao
import com.genshin.gachahelper.data.local.dao.PoolDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GachaDatabase {
        return Room.databaseBuilder(
            context,
            GachaDatabase::class.java,
            GachaDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration() // V1 简化，后续版本需使用 Migration
            .build()
    }

    @Provides
    fun provideAccountDao(database: GachaDatabase): AccountDao {
        return database.accountDao()
    }

    @Provides
    fun provideGachaRecordDao(database: GachaDatabase): GachaRecordDao {
        return database.gachaRecordDao()
    }

    @Provides
    fun providePoolDao(database: GachaDatabase): PoolDao {
        return database.poolDao()
    }
}
