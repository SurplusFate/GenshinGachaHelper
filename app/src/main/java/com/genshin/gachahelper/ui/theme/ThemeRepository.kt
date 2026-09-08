package com.genshin.gachahelper.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主题模式：跟随系统 / 强制白天 / 强制夜间
 */
enum class ThemeMode(val storedValue: Int) {
    FOLLOW_SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromStored(value: Int): ThemeMode =
            values().firstOrNull { it.storedValue == value } ?: FOLLOW_SYSTEM
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings_store")

/**
 * 设置项仓库（目前只有主题模式；后续可扩展语言等）
 */
@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
    }

    val themeModeFlow: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        ThemeMode.fromStored(prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.storedValue)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.storedValue
        }
    }
}
