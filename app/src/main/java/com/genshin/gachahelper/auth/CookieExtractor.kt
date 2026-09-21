package com.genshin.gachahelper.auth

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 米游社 Cookie 解析器
 *
 * 用途：从服务端下发的 Set-Cookie 响应头（或 WebView CookieManager）
 * 中提取关键登录凭证。
 *
 * ── 2026-09 修复 ──────────────────────────────────────────────
 * 新版通行证扫码登录（ma-cn-passport/app/queryQRLoginStatus）确认登录后，
 * 凭据**不在响应体**，而是通过 Set-Cookie 下发，且字段名带 _v2 后缀：
 *   ltuid_v2 / account_id_v2 / mid_v2 / ltoken_v2 / cookie_token_v2 /
 *   stoken_v2
 * 旧版解析器只认 stoken / ltuid / mid / cookie_token，导致新版登录后
 * 凭证全部为 null（真机表现为"扫码确认成功但 stoken=null"）。
 * 现在同时识别新旧两种字段名，优先取 v2。
 * ──────────────────────────────────────────────────────────────
 */
@Singleton
class CookieExtractor @Inject constructor() {

    data class MihoyoCredentials(
        val stoken: String?,
        val ltuid: String?,
        val mid: String?,
        val cookieToken: String?,
        val ltoken: String? = null
    ) {
        fun hasLogin(): Boolean =
            !stoken.isNullOrBlank() || !ltoken.isNullOrBlank() || !cookieToken.isNullOrBlank()
    }

    /**
     * 从 Cookie 字符串中提取米游社关键凭证。
     *
     * 同时兼容新旧字段名，v2 优先：
     *   stoken_v2 > stoken
     *   ltuid_v2 / account_id_v2 > ltuid / stuid / account_id
     *   mid_v2 / ltmid_v2 / account_mid_v2 > mid
     *   ltoken_v2 > ltoken
     *   cookie_token_v2 > cookie_token
     */
    fun extract(cookies: String?): MihoyoCredentials {
        if (cookies.isNullOrBlank()) return MihoyoCredentials(null, null, null, null)

        val cookieMap = parseCookies(cookies)

        fun pick(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> cookieMap[k]?.takeIf { it.isNotBlank() } }

        return MihoyoCredentials(
            stoken = pick("stoken_v2", "stoken")?.let { decodeValue(it) },
            ltuid = pick("ltuid_v2", "ltuid", "stuid", "account_id_v2", "account_id"),
            mid = pick("mid_v2", "mid", "ltmid_v2", "account_mid_v2"),
            cookieToken = pick("cookie_token_v2", "cookie_token"),
            ltoken = pick("ltoken_v2", "ltoken")
        )
    }

    /**
     * 解析 Cookie 字符串为 Map
     * "key1=value1; key2=value2" -> {key1: value1, key2: value2}
     *
     * 注意：Set-Cookie 常带属性（Path/Domain/Expires/Max-Age/HttpOnly/
     * Secure/SameSite），这些不是键值对语义。这里保留它们进 Map 无害
     * （不会与凭据键名冲突），但值中若含 "=" 需按首个 "=" 切分。
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
     * URL 解码值（stoken/ltoken 可能包含 URL 编码字符）
     */
    private fun decodeValue(value: String): String {
        return try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
