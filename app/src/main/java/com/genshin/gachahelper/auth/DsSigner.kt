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
     *
     * ── 2026-09 关键修复：&b 与 &q 必须**无条件**拼接 ──────────────
     * 签名原文格式固定为：
     *
     *   salt={salt}&t={t}&r={r}&b={body}&q={query}
     *
     * **即使 body 或 query 为空，对应的 `&b=` / `&q=` 也必须保留**
     * （留空值），不能省掉整段。
     *
     * 旧实现写成：
     *   if (body.isNotBlank()) append("&b=").append(body)
     *   if (query.isNotBlank())  append("&q=").append(query)
     *
     * 导致「有 body、无 query」的接口（如 verifyVerification）签名原文
     * 少了结尾的 `&q=`，md5 与服务端计算结果不一致，服务端判定请求非法，
     * 返回空 message 的 {"data":null,"message":"","retcode":-1}。
     *
     * 这解释了此前的诡异现象：dailyNote（有 query）与 createVerification
     * （有 query）都正常，唯独 verifyVerification（无 query）必然失败。
     * 参考 daidr/paimon-webext 的 getDS 实现确认该格式。
     * ──────────────────────────────────────────────────────────────
     *
     * r 为 100000-200000 随机数字
     */
    fun generateDS2(
        salt: String,
        body: String = "",
        query: String = ""
    ): String {
        val t = (System.currentTimeMillis() / 1000).toString()
        val r = (100000..200000).random()
        // 注意：&b 与 &q 无条件拼接（空值也要保留），见上方说明
        val raw = "salt=$salt&t=$t&r=$r&b=$body&q=$query"
        val c = md5(raw)
        // 写日志：把签名原文落盘，便于 verifyVerification 失败时反查服务端
        // 计算的签名究竟是什么（用户反馈："你就不能把完整流程记录到日志吗？"）
        AppLog.i(
            "DsSigner",
            "generateDS2",
            "salt=$salt t=$t r=$r c=$c\n" +
                "raw_prefix=${raw.take(160)}" +
                if (raw.length > 160) "...[total=${raw.length}]" else ""
        )
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
