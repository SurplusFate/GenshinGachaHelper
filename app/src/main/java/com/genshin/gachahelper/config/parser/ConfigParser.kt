package com.genshin.gachahelper.config.parser

import com.genshin.gachahelper.config.model.ApiConfig
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 接口配置 JSON 解析器
 */
@Singleton
class ConfigParser @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 解析 JSON 字符串为 ApiConfig
     * @throws IllegalArgumentException 如果解析失败或校验失败
     */
    fun parse(jsonString: String): ApiConfig {
        val config = try {
            json.decodeFromString<ApiConfig>(jsonString)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON 解析失败: ${e.message}")
        }

        val error = config.validate()
        if (error != null) {
            throw IllegalArgumentException("配置校验失败: $error")
        }

        return config
    }

    /**
     * 将 ApiConfig 序列化为 JSON 字符串
     */
    fun toJson(config: ApiConfig): String {
        return json.encodeToString(ApiConfig.serializer(), config)
    }
}
