package com.genshin.gachahelper.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抽卡接口客户端（硬编码米游社官方 getGachaLog API）
 *
 * 之前通过 ConfigStore 可自定义 API 配置，但官方抽卡 API URL/参数/响应结构
 * 是固定的，自定义配置没有任何可观察效果（UI 显示"导入成功"，同步时实际走
 * 的仍是默认值）。改为直接硬编码官方接口结构，减少一层间接。
 */
@Singleton
class GachaApiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        /** 米游社官方抽卡记录 API */
        const val BASE_URL =
            "https://public-operation-hk4e.mihoyo.com/gacha_info/api/getGachaLog"

        /** 每 页大小（官方 API 固定 20） */
        const val PAGE_SIZE = 20
    }

    /**
     * 发送抽卡记录请求
     * @param placeholders 占位符替换映射（authkey, uid, region, page, gacha_type, end_id 等）
     * @return 原始 JSON 响应字符串
     */
    suspend fun fetchGachaPage(
        placeholders: Map<String, String>
    ): Result<String> {
        return try {
            val url = buildUrl(placeholders)
            val request = Request.Builder().url(url).get().build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
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
     * 构建 GET 请求 URL。官方 API 固定用 GET，参数名与官方一致。
     * authkey 含 +/= 等特殊字符，必须 URL 编码。
     */
    private fun buildUrl(placeholders: Map<String, String>): String {
        val authkey = placeholders["authkey"] ?: ""
        val region = placeholders["region"] ?: ""
        val gachaType = placeholders["gacha_type"] ?: ""
        val page = placeholders["page"] ?: "1"
        val endId = placeholders["end_id"] ?: "0"

        val params = listOf(
            "authkey" to authkey,
            "authkey_ver" to "1",
            "sign_type" to "2",
            "auth_appid" to "webview_gacha",
            "lang" to "zh-cn",
            "device_type" to "mobile",
            "plat_type" to "android",
            "region" to region,
            "gacha_type" to gachaType,
            "page" to page,
            "size" to PAGE_SIZE.toString(),
            "end_id" to endId
        ).joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$BASE_URL?$params"
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
