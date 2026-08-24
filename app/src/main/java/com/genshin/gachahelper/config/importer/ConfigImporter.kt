package com.genshin.gachahelper.config.importer

import android.content.Context
import android.net.Uri
import com.genshin.gachahelper.config.model.ApiConfig
import com.genshin.gachahelper.config.parser.ConfigParser
import com.genshin.gachahelper.config.store.ConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 配置导入器
 * 支持从文件 URI 或 JSON 字符串导入配置
 */
@Singleton
class ConfigImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: ConfigParser,
    private val configStore: ConfigStore
) {
    /**
     * 从文件 URI 导入配置
     * @throws IllegalArgumentException 如果解析或校验失败
     */
    suspend fun importFromUri(uri: Uri): ApiConfig = withContext(Dispatchers.IO) {
        val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        } ?: throw IllegalArgumentException("无法读取文件")

        importFromString(jsonString)
    }

    /**
     * 从 JSON 字符串导入配置
     * @throws IllegalArgumentException 如果解析或校验失败
     */
    suspend fun importFromString(jsonString: String): ApiConfig = withContext(Dispatchers.IO) {
        val config = parser.parse(jsonString)
        configStore.saveConfig(config)
        config
    }

    /**
     * 仅校验不保存
     */
    fun validateConfig(jsonString: String): Result<ApiConfig> {
        return try {
            val config = parser.parse(jsonString)
            Result.success(config)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }
}
