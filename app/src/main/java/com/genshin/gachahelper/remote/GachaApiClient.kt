package com.genshin.gachahelper.remote

import android.content.Context
import com.genshin.gachahelper.config.model.ApiConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抽卡接口客户端
 * 基于配置文件动态构建请求，不硬编码任何 URL 或字段名
 */
@Singleton
class GachaApiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    /**
     * 发送抽卡记录请求
     * @param config 接口配置
     * @param placeholders 占位符替换映射（authkey, uid, page, gacha_type 等）
     * @return 原始 JSON 响应字符串
     */
    suspend fun fetchGachaPage(
        config: ApiConfig,
        placeholders: Map<String, String>
    ): Result<String> {
        return try {
            val url = buildUrl(config, placeholders)
            val requestBuilder = Request.Builder().url(url)

            // 添加请求头
            config.api.headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, replacePlaceholders(value, placeholders))
            }

            // 根据方法构建请求体
            val request = when (config.api.method.uppercase()) {
                "POST" -> {
                    val body = buildPostBody(config, placeholders)
                    requestBuilder.post(body.toRequestBody())
                        .addHeader("Content-Type", "application/json")
                        .build()
                }
                else -> requestBuilder.get().build()
            }

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                // 记录错误日志
                logError(url, response.code, body)
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            } else {
                Result.success(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 构建 GET 请求 URL
     */
    private fun buildUrl(config: ApiConfig, placeholders: Map<String, String>): String {
        val baseUrl = replacePlaceholders(config.api.url, placeholders)

        if (config.api.method.uppercase() != "GET") return baseUrl

        // 构建查询参数（对每个参数值进行 URL 编码，特别是 authkey 含 +/= 等特殊字符）
        val params = config.params.map { (key, value) ->
            val rawValue = replacePlaceholders(value, placeholders)
            "$key=${URLEncoder.encode(rawValue, "UTF-8")}"
        }.joinToString("&")

        return if (params.isNotBlank()) {
            val separator = if (baseUrl.contains("?")) "&" else "?"
            "$baseUrl$separator$params"
        } else {
            baseUrl
        }
    }

    /**
     * 构建 POST 请求体
     */
    private fun buildPostBody(config: ApiConfig, placeholders: Map<String, String>): String {
        val params = config.params.map { (key, value) ->
            "\"$key\": \"${replacePlaceholders(value, placeholders)}\""
        }.joinToString(",")
        return "{$params}"
    }

    /**
     * 替换字符串中的占位符 {xxx}
     */
    private fun replacePlaceholders(template: String, placeholders: Map<String, String>): String {
        var result = template
        placeholders.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        return result
    }

    /**
     * 记录错误日志到本地文件
     */
    private fun logError(url: String, statusCode: Int, responseBody: String) {
        try {
            val errorDir = File(context.filesDir, "errors")
            if (!errorDir.exists()) errorDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(errorDir, "error_${timestamp}.txt")
            file.writeText(
                "URL: $url\n" +
                "Status: $statusCode\n" +
                "Time: ${Date()}\n" +
                "Response:\n$responseBody\n"
            )
        } catch (_: Exception) {
            // 日志写入失败不影响主流程
        }
    }

    /**
     * 获取错误日志文件列表
     */
    fun getErrorLogs(): List<File> {
        val errorDir = File(context.filesDir, "errors")
        return if (errorDir.exists()) {
            errorDir.listFiles()?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }
}
