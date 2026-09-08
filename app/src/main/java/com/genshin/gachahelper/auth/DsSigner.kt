package com.genshin.gachahelper.auth

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Singleton

/**
 * DS 签名工具（统一管理）
 *
 * 两种算法：
 * - DS1: salt={salt}&t={t}&r={r}  （r 为 6 位随机字母数字）
 * - DS2: salt={salt}&t={t}&r={r}&b={body}&q={query}  （r 为 100000-200000 随机数字）
 *
 * salt 选择规则：
 * - client_type=2 → K2 salt + DS1
 * - client_type=4 → LK2 salt + DS1（通用），getTokenByGameToken 特例用 6X + DS2
 * - client_type=5 → 4X/6X salt + DS2（通用）
 * - 特定 API 可能覆盖此规则（如 genAuthKey 用 LK2 + DS1）
 *
 * salt 来源：UIGF-org/mihoyo-api-collect#1
 * 4X 和 6X 是版本无关的（不随版本变化）
 * K2 和 LK2 随米游社版本变化
 */
@Singleton
object DsSigner {

    object Salt {
        // 米游社版本号
        const val APP_VERSION = "2.71.1"
        // LK2 salt（版本 2.71.1），用于 genAuthKey（DS1, client_type=5 特例）
        const val LK2 = "EJncUPGnOHajenjLhBOsdpwEMZmiCmQX"
        // 4X salt（版本无关），用于 client_type=5 的 DS2 请求
        const val X4 = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs"
        // 6X salt（版本无关），用于 getTokenByGameToken（DS2, client_type=4 特例）
        const val X6 = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v"
        // K2 salt（版本 2.71.1），用于 client_type=2 的 DS1 请求
        const val K2 = "rtvTthKxEyreVXQCnhluFgLXPOFKPHlA"
        // PROD salt（账号相关 API）
        const val PROD = "JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS"
    }

    /**
     * 标准 User-Agent（带 miHoYoBBS 版本号后缀）
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; M2101K9C Build/TKQ1.220829.002; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
        "Chrome/108.0.5359.128 Mobile Safari/537.36 miHoYoBBS/${Salt.APP_VERSION}"

    /**
     * 生成 DS1 签名（不含 body/query）
     * 格式: t,r,md5(salt={salt}&t={t}&r={r})
     * r 为 6 位随机字母数字
     */
    fun generateDS1(salt: String): String {
        val t = (System.currentTimeMillis() / 1000).toString()
        val r = randomString(6)
        val raw = "salt=$salt&t=$t&r=$r"
        val c = md5(raw)
        return "$t,$r,$c"
    }

    /**
     * 生成 DS2 签名（含 body 和/或 query）
     * 格式: t,r,md5(salt={salt}&t={t}&r={r}&b={body}&q={query})
     * r 为 100000-200000 随机数字
     */
    fun generateDS2(
        salt: String,
        body: String = "",
        query: String = ""
    ): String {
        val t = (System.currentTimeMillis() / 1000).toString()
        var r = (100000..200000).random()
        if (r == 100000) r = 642367
        val raw = buildString {
            append("salt=").append(salt)
            append("&t=").append(t)
            append("&r=").append(r)
            if (body.isNotBlank()) append("&b=").append(body)
            if (query.isNotBlank()) append("&q=").append(query)
        }
        val c = md5(raw)
        return "$t,$r,$c"
    }

    /**
     * 生成随机设备 ID
     */
    fun randomDeviceId(): String {
        return UUID.randomUUID().toString().lowercase(Locale.ROOT)
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
