package com.genshin.gachahelper.config.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.genshin.gachahelper.config.model.ApiConfig
import com.genshin.gachahelper.config.parser.ConfigParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "api_config")

/**
 * 接口配置存储
 * 负责配置的持久化（DataStore）和默认配置加载
 */
@Singleton
class ConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: ConfigParser
) {
    private object Keys {
        val CONFIG_JSON = stringPreferencesKey("config_json")
    }

    /**
     * 当前配置的 Flow（自定义配置优先，否则使用默认配置）
     */
    val configFlow: Flow<ApiConfig> = context.dataStore.data.map { prefs ->
        val savedJson = prefs[Keys.CONFIG_JSON]
        if (!savedJson.isNullOrBlank()) {
            try {
                parser.parse(savedJson)
            } catch (_: Exception) {
                getDefaultConfig()
            }
        } else {
            getDefaultConfig()
        }
    }

    /**
     * 获取当前配置（挂起函数，一次性读取）
     */
    suspend fun getCurrentConfig(): ApiConfig {
        val prefs = context.dataStore.data.first()
        val savedJson = prefs[Keys.CONFIG_JSON]
        return if (!savedJson.isNullOrBlank()) {
            try {
                parser.parse(savedJson)
            } catch (_: Exception) {
                getDefaultConfig()
            }
        } else {
            getDefaultConfig()
        }
    }

    /**
     * 保存新配置
     */
    suspend fun saveConfig(config: ApiConfig) {
        val json = parser.toJson(config)
        context.dataStore.edit { prefs ->
            prefs[Keys.CONFIG_JSON] = json
        }
    }

    /**
     * 恢复默认配置
     */
    suspend fun resetToDefault() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CONFIG_JSON)
        }
    }

    /**
     * 从 assets 加载默认配置
     */
    fun getDefaultConfig(): ApiConfig {
        val defaultJson = context.assets.open("default_api_config.json")
            .bufferedReader()
            .use { it.readText() }
        return parser.parse(defaultJson)
    }

    /**
     * 导出当前配置为 JSON 字符串
     */
    suspend fun exportConfig(): String {
        val prefs = context.dataStore.data.first()
        val savedJson = prefs[Keys.CONFIG_JSON]
        return if (!savedJson.isNullOrBlank()) {
            savedJson
        } else {
            parser.toJson(getDefaultConfig())
        }
    }
}
