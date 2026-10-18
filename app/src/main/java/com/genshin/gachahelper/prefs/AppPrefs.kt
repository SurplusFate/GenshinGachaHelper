package com.genshin.gachahelper.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局轻量偏好，与提醒链路各自的 DataStore 隔离，避免互相干扰。
 *
 * 当前字段只有「隐藏后台」：开启后本应用从「最近任务列表」中消失，用户划不到它的卡片，
 * 「一键清理 / 划掉后台」就无从杀起——这是 App 侧对抗清后台最彻底的手段（根本没有卡片可划）。
 * 实际生效在 [com.genshin.gachahelper.ui.MainActivity] 的 onCreate / onResume 里调用
 * [android.app.ActivityManager.AppTask.setExcludeFromRecents]（框架的 Activity 本身没有该方法，
 * 必须拿到当前 AppTask 才能隐藏最近任务卡片）。
 */
private val Context.appPrefsStore by preferencesDataStore(name = "app_prefs")

@Singleton
class AppPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_HIDE_FROM_RECENTS = booleanPreferencesKey("hide_from_recents")
    }

    val hideFromRecentsFlow: Flow<Boolean> = context.appPrefsStore.data.map { prefs ->
        prefs[KEY_HIDE_FROM_RECENTS] ?: false
    }

    suspend fun isHideFromRecents(): Boolean =
        context.appPrefsStore.data.first()[KEY_HIDE_FROM_RECENTS] ?: false

    suspend fun setHideFromRecents(v: Boolean) {
        context.appPrefsStore.edit { it[KEY_HIDE_FROM_RECENTS] = v }
    }
}
