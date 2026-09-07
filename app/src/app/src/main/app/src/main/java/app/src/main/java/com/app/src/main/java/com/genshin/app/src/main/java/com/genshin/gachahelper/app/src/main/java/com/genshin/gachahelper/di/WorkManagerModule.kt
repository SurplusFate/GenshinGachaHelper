package com.genshin.gachahelper.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 提供 WorkManager 实例。
 *
 * GachaApp 已实现 Configuration.Provider，on-demand 初始化模式下
 * WorkManager.getInstance(context) 即可返回由 HiltWorkerFactory 驱动的实例。
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
