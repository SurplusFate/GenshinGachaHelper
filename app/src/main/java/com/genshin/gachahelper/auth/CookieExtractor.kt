package com.genshin.gachahelper.auth

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 米游社 Cookie 解析器
 * 从 WebView 的 CookieManager 获取的 Cookie 字符串中提取关键凭证
 */
@Singleton
class CookieExtractor @Inject constructor() {

    data class MihoyoCredentials(
        val stoken: String?,
        val ltuid: String?,
        val mid: String?,
        val cookieToken: String?
    ) {
        fun hasLogin(): Boolean = !stoken.isNullOrBlank()
    }

    /**
     * 从 Cookie 字符串中提取米游社关键凭证
     */
    fun extract(cookies: String?): MihoyoCredentials {
        if (cookies.isNullOrBlank()) return MihoyoCredentials(null, null, null, null)

        val cookieMap = parseCookies(cookies)

        return MihoyoCredentials(
            stoken = cookieMap["stoken"]?.let { decodeValue(it) },
            ltuid = cookieMap["ltuid"],
            mid = cookieMap["mid"],
            cookieToken = cookieMap["cookie_token"]
        )
    }

    /**
     * 解析 Cookie 字符串为 Map
     * "key1=value1; key2=value2" -> {key1: value1, key2: value2}
     */
    private fun parseCookies(cookieString: String): Map<String, String> {
        return cookieString.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate {
                val idx = it.indexOf("=")
                it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }
    }

    /**
     * URL 解码值（stoken 可能包含 URL 编码字符）
     */
    private fun decodeValue(value: String): String {
        return try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
