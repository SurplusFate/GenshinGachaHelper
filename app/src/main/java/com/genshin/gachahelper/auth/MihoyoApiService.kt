package com.genshin.gachahelper.auth

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = -1, val rawResponse: String = "", val step: String = "") : ApiResult<Nothing>()
}

data class GameRole(
    val uid: String,
    val region: String,
    val nickname: String,
    val level: Int
)

data class TokenInfo(
    val stoken: String,
    val mid: String
)

data class MultiTokenInfo(
    val stoken: String,
    val ltoken: String
)

data class QrCodeData(
    val url: String,
    val ticket: String,
    val device: String
)

data class QrCodeStatus(
    val stat: String,
    val uid: String?,
    val gameToken: String?,
    val rawResponse: String
)

/**
 * 米游社通行证扫码登录结果（新 API）
 * status: Created / Scanned / Confirmed
 * tokens 和 user_info 仅在 Confirmed 时存在
 */
data class PassportQrStatus(
    val status: String,
    val stoken: String?,
    val mid: String?,
    val aid: String?,
    val rawResponse: String
)

/**
 * 米游社 API 服务
 *
 * 认证流程（通行证扫码登录，参考 BTMuli/TeyvatGuide + gsuid_core 开源实现）：
 * 1. createQRLogin 获取二维码（passport API，无需 DS）
 * 2. queryQRLoginStatus 轮询扫码状态（passport API，无需 DS）
 *    → Confirmed 后直接返回 tokens（含 stoken），无需再调 getTokenByGameToken
 * 3. getUserGameRolesByCookie 获取角色（需 DS2 + 4X salt + Cookie, client_type=5）
 * 4. genAuthKey 生成 authkey（需 DS1 + LK2 salt + Cookie, client_type=5）
 *
 * 旧 hk4e SDK 扫码流程已废弃（getTokenByGameToken 返回 -5300）
 *
 * Salt 来源：UIGF-org/mihoyo-api-collect#1
 * 设备 ID：UUID v3（基于 ANDROID_ID），持久化存储
 * 设备指纹：通过 public-data-api.mihoyo.com/device-fp/api/getFp 获取
 */
@Singleton
class MihoyoApiService @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceFpService: DeviceFpService,
    private val client: OkHttpClient
) {
    companion object {
        // ============== 通行证扫码登录（新 API，推荐） ==============
        // 无 DS 签名，确认后直接返回 stoken，无需再换 token
        private const val PASSPORT_BASE = "https://passport-api.mihoyo.com"
        private const val API_PASSPORT_CREATE_QR =
            "$PASSPORT_BASE/account/ma-cn-passport/app/createQRLogin"
        private const val API_PASSPORT_QUERY_QR =
            "$PASSPORT_BASE/account/ma-cn-passport/app/queryQRLoginStatus"

        // 用 cookie_token 换 stoken（通行证 API，验证码/密码登录场景）
        private const val API_GET_STOKEN_BY_COOKIE =
            "$PASSPORT_BASE/account/auth/api/getAccountInfoByCookieToken"

        // 通行证 app_id 和 client_type
        private const val PASSPORT_APP_ID = "ddxf5dufpuyo"
        private const val PASSPORT_CLIENT_TYPE = "3"
        private const val PASSPORT_UA = "HYPContainer/1.3.3.182"

        // ============== 用 stoken 换 cookie_token / ltoken ==============
        // 旧端点 api-takumi.mihoyo.com 已返回 -5300，改用 passport-api
        private const val API_GET_COOKIE_BY_STOKEN =
            "$PASSPORT_BASE/account/auth/api/getCookieAccountInfoBySToken"
        private const val API_GET_LTOKEN_BY_STOKEN =
            "$PASSPORT_BASE/account/auth/api/getLTokenBySToken"

        // ============== 旧 hk4e SDK 扫码登录（已废弃，保留备用） ==============
        private const val API_QRCODE_FETCH =
            "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/fetch"
        private const val API_QRCODE_QUERY =
            "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/query"
        // Game Token 换 stoken（已废弃，返回 -5300）
        private const val API_GET_TOKEN_BY_GAME_TOKEN =
            "https://api-takumi.mihoyo.com/account/ma-cn-session/app/getTokenByGameToken"
        // login_ticket 换 stoken + ltoken（GET，无需特殊头）
        private const val API_GET_MULTI_TOKEN_BY_LOGIN_TICKET =
            "https://api-takumi.mihoyo.com/auth/api/getMultiTokenByLoginTicket"
        // 获取游戏角色列表（需 DS2 + 4X salt + Cookie）
        private const val API_GET_GAME_ROLES =
            "https://api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie"
        // 生成 authkey（需 DS1 + LK2 salt + Cookie）
        private const val API_GEN_AUTH_KEY =
            "https://api-takumi.miyoushe.com/binding/api/genAuthKey"

        // 原神 app_id（用于旧 hk4e 扫码登录）
        private const val APP_ID = "4"
        // getTokenByGameToken 需要的 app_id（已废弃）
        private const val RPC_APP_ID = "bll8iq97cem8"
        // client_type=5（web 通用）
        private const val CLIENT_TYPE_WEB = "5"
        private const val CLIENT_TYPE_TOKEN = "4"
    }

    // ==================================================================
    // 通行证扫码登录（新 API，推荐）
    // 参考 BTMuli/TeyvatGuide + gsuid_core
    // 无 DS 签名，确认后直接返回 stoken
    // ==================================================================

    // ------------------------------------------------------------------
    // P1. 创建通行证二维码（无 DS，无 device_fp）
    // ------------------------------------------------------------------
    suspend fun createPassportQr(): ApiResult<QrCodeData> = withContext(Dispatchers.IO) {
        try {
            val device = authRepository.getOrCreateDeviceId()

            val request = Request.Builder()
                .url(API_PASSPORT_CREATE_QR)
                .addHeader("x-rpc-device_id", device)
                .addHeader("user-agent", PASSPORT_UA)
                .addHeader("x-rpc-app_id", PASSPORT_APP_ID)
                .addHeader("x-rpc-client_type", PASSPORT_CLIENT_TYPE)
                .addHeader("Accept", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "passportCreate")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "passportCreate")
            }

            val data = json.getAsJsonObject("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "passportCreate")

            val url = data.get("url")?.asString
                ?: return@withContext ApiResult.Error("响应缺少 data.url", -1, respBody, "passportCreate")
            val ticket = data.get("ticket")?.asString
                ?: return@withContext ApiResult.Error("响应缺少 data.ticket", -1, respBody, "passportCreate")

            ApiResult.Success(QrCodeData(url, ticket, device))
        } catch (e: Exception) {
            ApiResult.Error("创建通行证二维码异常: ${e.message}", -1, "", "passportCreate")
        }
    }

    // ------------------------------------------------------------------
    // P2. 查询通行证扫码状态（无 DS，无 device_fp）
    // status: Created / Scanned / Confirmed
    // Confirmed 时返回 tokens 和 user_info
    // ------------------------------------------------------------------
    suspend fun queryPassportQrStatus(ticket: String, device: String): ApiResult<PassportQrStatus> =
        withContext(Dispatchers.IO) {
            try {
                val body = """{"ticket":"$ticket"}"""

                val request = Request.Builder()
                    .url(API_PASSPORT_QUERY_QR)
                    .addHeader("x-rpc-device_id", device)
                    .addHeader("user-agent", PASSPORT_UA)
                    .addHeader("x-rpc-app_id", PASSPORT_APP_ID)
                    .addHeader("x-rpc-client_type", PASSPORT_CLIENT_TYPE)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val respBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "passportQuery")
                }

                val json = JsonParser.parseString(respBody).asJsonObject
                val retcode = json.get("retcode")?.asInt ?: -1

                if (retcode != 0) {
                    val msg = json.get("message")?.asString ?: "未知错误"
                    return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "passportQuery")
                }

                val data = json.getAsJsonObject("data")
                    ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "passportQuery")

                val status = data.get("status")?.asString ?: "Created"

                var stoken: String? = null
                var mid: String? = null
                var aid: String? = null

                if (status == "Confirmed") {
                    val userInfo = data.getAsJsonObject("user_info")
                    mid = userInfo?.get("mid")?.asString
                    aid = userInfo?.get("aid")?.asString

                    // tokens 数组字段是 name（不是 token_type）
                    // 国服通常返回 name="stoken_v2" 或 name="stoken"
                    val tokensArray = data.getAsJsonArray("tokens")
                    if (tokensArray != null) {
                        for (tokenObj in tokensArray) {
                            val obj = tokenObj.asJsonObject
                            val name = obj.get("name")?.asString ?: ""
                            val token = obj.get("token")?.asString
                            if (token != null && stoken == null) {
                                stoken = token
                            }
                        }
                    }
                }

                ApiResult.Success(PassportQrStatus(status, stoken, mid, aid, respBody))
            } catch (e: Exception) {
                ApiResult.Error("查询通行证扫码状态异常: ${e.message}", -1, "", "passportQuery")
            }
        }

    // ------------------------------------------------------------------
    // P3. 用 stoken 换 cookie_token（passport API，无需 DS）
    // 参考 gsuid_core qrlogin.py + mihoyo-api-collect
    // ------------------------------------------------------------------
    suspend fun getCookieTokenByStoken(
        stoken: String,
        uid: String,
        mid: String?
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val url = "$API_GET_COOKIE_BY_STOKEN?stoken=$stoken&uid=$uid"
            // 构造 Cookie: stuid={uid};stoken={stoken};mid={mid}
            val cookieStr = buildString {
                append("stuid=$uid;stoken=$stoken")
                if (!mid.isNullOrBlank()) append(";mid=$mid")
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Cookie", cookieStr)
                .addHeader("User-Agent", PASSPORT_UA)
                .addHeader("x-rpc-app_id", PASSPORT_APP_ID)
                .addHeader("x-rpc-client_type", PASSPORT_CLIENT_TYPE)
                .addHeader("x-rpc-device_id", authRepository.getOrCreateDeviceId())
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "getCookieToken")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "getCookieToken")
            }

            val data = json.getAsJsonObject("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "getCookieToken")

            val cookieToken = data.get("cookie_token")?.asString
                ?: return@withContext ApiResult.Error("响应缺少 cookie_token", -1, respBody, "getCookieToken")

            ApiResult.Success(cookieToken)
        } catch (e: Exception) {
            ApiResult.Error("换取 cookie_token 异常: ${e.message}", -1, "", "getCookieToken")
        }
    }

    // ------------------------------------------------------------------
    // P3b. 用 stoken 换 ltoken（passport API，POST，无需 DS）
    // 参考 TeyvatGuide getLTokenBySToken
    // ------------------------------------------------------------------
    suspend fun getLTokenByStoken(
        stoken: String,
        uid: String,
        mid: String?
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val cookieStr = buildString {
                append("stuid=$uid;stoken=$stoken")
                if (!mid.isNullOrBlank()) append(";mid=$mid")
            }

            val request = Request.Builder()
                .url(API_GET_LTOKEN_BY_STOKEN)
                .addHeader("Cookie", cookieStr)
                .addHeader("User-Agent", PASSPORT_UA)
                .addHeader("x-rpc-app_id", PASSPORT_APP_ID)
                .addHeader("x-rpc-client_type", PASSPORT_CLIENT_TYPE)
                .addHeader("x-rpc-device_id", authRepository.getOrCreateDeviceId())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "getLToken")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "getLToken")
            }

            val ltoken = json.getAsJsonObject("data")?.get("ltoken")?.asString
                ?: return@withContext ApiResult.Error("响应缺少 ltoken", -1, respBody, "getLToken")

            ApiResult.Success(ltoken)
        } catch (e: Exception) {
            ApiResult.Error("换取 ltoken 异常: ${e.message}", -1, "", "getLToken")
        }
    }

    // ------------------------------------------------------------------
    // P3c. 用 cookie_token 换 stoken（passport API，验证码/密码登录场景）
    // WebView 验证码/密码登录后只有 cookie_token + ltoken，没有 stoken，
    // 但 genAuthKey 需要 stoken 才能通过（retcode -100），
    // 所以需要用 cookie_token 再去通行证换一份 stoken。
    // ------------------------------------------------------------------
    suspend fun getStokenByCookieToken(
        cookieToken: String,
        uid: String,
        mid: String?
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val cookieStr = buildString {
                append("cookie_token_v2=$cookieToken")
                append("; stuid=$uid; ltuid=$uid")
                if (!mid.isNullOrBlank()) append("; mid=$mid")
            }

            val request = Request.Builder()
                .url(API_GET_STOKEN_BY_COOKIE)
                .addHeader("Cookie", cookieStr)
                .addHeader("User-Agent", PASSPORT_UA)
                .addHeader("x-rpc-app_id", PASSPORT_APP_ID)
                .addHeader("x-rpc-client_type", PASSPORT_CLIENT_TYPE)
                .addHeader("x-rpc-device_id", authRepository.getOrCreateDeviceId())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "getStokenByCookie")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "getStokenByCookie")
            }

            val data = json.getAsJsonObject("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "getStokenByCookie")

            // 通行证返回的可能是 "stoken" 或列表里的 tokens[].token
            val stoken = data.get("stoken")?.asString
                ?: data.getAsJsonArray("tokens")?.firstOrNull()?.asJsonObject?.get("token")?.asString
                ?: return@withContext ApiResult.Error("响应中未找到 stoken", -1, respBody, "getStokenByCookie")

            ApiResult.Success(stoken)
        } catch (e: Exception) {
            ApiResult.Error("用 cookie_token 换 stoken 异常: ${e.message}", -1, "", "getStokenByCookie")
        }
    }

    // ------------------------------------------------------------------
    // 1. 获取二维码（无 DS，需 device_fp 防风控）
    // ------------------------------------------------------------------
    suspend fun fetchQrCode(): ApiResult<QrCodeData> = withContext(Dispatchers.IO) {
        try {
            val device = authRepository.getOrCreateDeviceId()
            val deviceFp = deviceFpService.getOrCreateDeviceFp()
            val body = """{"app_id":"$APP_ID","device":"$device","device_fp":"$deviceFp"}"""

            val request = Request.Builder()
                .url(API_QRCODE_FETCH)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("x-rpc-device_fp", deviceFp ?: "")
                .addHeader("x-rpc-device_id", device)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "fetch")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "fetch")
            }

            val data = json.getAsJsonObject("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "fetch")

            val url = data.get("url")?.asString
                ?: return@withContext ApiResult.Error("响应缺少 data.url", -1, respBody, "fetch")

            val ticket = Regex("ticket=([^&]+)").find(url)?.groupValues?.get(1)
                ?: return@withContext ApiResult.Error("无法提取 ticket", -1, respBody, "fetch")

            ApiResult.Success(QrCodeData(url, ticket, device))
        } catch (e: Exception) {
            ApiResult.Error("获取二维码异常: ${e.message}", -1, "", "fetch")
        }
    }

    // ------------------------------------------------------------------
    // 2. 轮询扫码状态（无 DS，需 device_fp 防风控）
    // ------------------------------------------------------------------
    suspend fun queryQrCodeStatus(ticket: String, device: String): ApiResult<QrCodeStatus> =
        withContext(Dispatchers.IO) {
            try {
                val deviceFp = deviceFpService.getOrCreateDeviceFp()
                val body = """{"app_id":"$APP_ID","device":"$device","ticket":"$ticket","device_fp":"$deviceFp"}"""

                val request = Request.Builder()
                    .url(API_QRCODE_QUERY)
                    .addHeader("Content-Type", "application/json;charset=utf-8")
                    .addHeader("Accept", "application/json")
                    .addHeader("x-rpc-device_fp", deviceFp ?: "")
                    .addHeader("x-rpc-device_id", device)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val respBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "query")
                }

                val json = JsonParser.parseString(respBody).asJsonObject
                val retcode = json.get("retcode")?.asInt ?: -1

                if (retcode == -106) {
                    return@withContext ApiResult.Error("二维码已过期", retcode, respBody, "query")
                }

                if (retcode != 0) {
                    val msg = json.get("message")?.asString ?: "未知错误"
                    return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "query")
                }

                val data = json.getAsJsonObject("data")
                    ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "query")

                val stat = data.get("stat")?.asString ?: "Init"
                var uid: String? = null
                var gameToken: String? = null

                if (stat == "Confirmed") {
                    val payload = data.getAsJsonObject("payload")
                    if (payload != null) {
                        val raw = payload.get("raw")?.asString
                        if (!raw.isNullOrBlank()) {
                            try {
                                val rawJson = JsonParser.parseString(raw).asJsonObject
                                uid = rawJson.get("uid")?.asString
                                gameToken = rawJson.get("token")?.asString
                            } catch (_: Exception) { }
                        }
                    }
                }

                ApiResult.Success(QrCodeStatus(stat, uid, gameToken, respBody))
            } catch (e: Exception) {
                ApiResult.Error("查询扫码状态异常: ${e.message}", -1, "", "query")
            }
        }

    // ------------------------------------------------------------------
    // 3. Game Token 换 stoken（需 DS2 + 6X salt + x-rpc 头）
    // ------------------------------------------------------------------
    suspend fun getTokenByGameToken(
        accountId: String,
        gameToken: String
    ): ApiResult<TokenInfo> = withContext(Dispatchers.IO) {
        try {
            val accountIdLong = accountId.toLongOrNull()
            val body = if (accountIdLong != null) {
                """{"account_id":$accountIdLong,"game_token":"$gameToken"}"""
            } else {
                """{"account_id":"$accountId","game_token":"$gameToken"}"""
            }

            val deviceId = authRepository.getOrCreateDeviceId()
            val deviceFp = deviceFpService.getOrCreateDeviceFp()
            val ds = DsSigner.generateDS2(
                salt = DsSigner.Salt.X6,
                body = body
            )

            val request = Request.Builder()
                .url(API_GET_TOKEN_BY_GAME_TOKEN)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", DsSigner.USER_AGENT)
                .addHeader("x-rpc-app_version", DsSigner.Salt.APP_VERSION)
                .addHeader("x-rpc-client_type", CLIENT_TYPE_TOKEN)
                .addHeader("x-rpc-device_id", deviceId)
                .addHeader("x-rpc-device_fp", deviceFp ?: "")
                .addHeader("x-rpc-device_name", "GachaHelper")
                .addHeader("x-rpc-device_model", "GachaHelper")
                .addHeader("x-rpc-sys_version", "13")
                .addHeader("x-rpc-game_biz", "bbs_cn")
                .addHeader("x-rpc-app_id", RPC_APP_ID)
                .addHeader("DS", ds)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "getToken")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "getToken")
            }

            val data = json.getAsJsonObject("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "getToken")

            val tokenObj = data.getAsJsonObject("token")
            val stoken = tokenObj?.get("token")?.asString
                ?: return@withContext ApiResult.Error("响应缺少 token", -1, respBody, "getToken")

            val mid = data.getAsJsonObject("user_info")?.get("mid")?.asString ?: ""

            ApiResult.Success(TokenInfo(stoken, mid))
        } catch (e: Exception) {
            ApiResult.Error("换取 stoken 异常: ${e.message}", -1, "", "getToken")
        }
    }

    // ------------------------------------------------------------------
    // 3b. Login Ticket 换 stoken + ltoken（GET，无需特殊头）
    // ------------------------------------------------------------------
    suspend fun getMultiTokenByLoginTicket(
        loginTicket: String,
        uid: String
    ): ApiResult<MultiTokenInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "$API_GET_MULTI_TOKEN_BY_LOGIN_TICKET?token_types=3&login_ticket=$loginTicket&uid=$uid"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", DsSigner.USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "multiToken")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "multiToken")
            }

            val listArray = json.getAsJsonObject("data")?.getAsJsonArray("list")
                ?: return@withContext ApiResult.Error("响应缺少 data.list", -1, respBody, "multiToken")

            var stoken: String? = null
            var ltoken: String? = null
            for (item in listArray) {
                val obj = item.asJsonObject
                val name = obj.get("name")?.asString ?: ""
                val token = obj.get("token")?.asString ?: ""
                when (name) {
                    "stoken" -> stoken = token
                    "ltoken" -> ltoken = token
                }
            }

            if (stoken.isNullOrBlank()) {
                return@withContext ApiResult.Error("响应中未找到 stoken", -1, respBody, "multiToken")
            }

            ApiResult.Success(MultiTokenInfo(stoken, ltoken ?: ""))
        } catch (e: Exception) {
            ApiResult.Error("换取 stoken 异常: ${e.message}", -1, "", "multiToken")
        }
    }

    // ------------------------------------------------------------------
    // 4. 获取游戏角色列表（DS2 + Cookie + 4X salt）
    // ------------------------------------------------------------------
    suspend fun getGameRoles(): ApiResult<List<GameRole>> = withContext(Dispatchers.IO) {
        try {
            val cookie = authRepository.buildCookieString()
            if (cookie.isBlank()) {
                return@withContext ApiResult.Error("未登录，缺少有效凭证", -1, "", "getRoles")
            }

            val query = "game_biz=hk4e_cn"
            val url = "$API_GET_GAME_ROLES?$query"

            val ds = DsSigner.generateDS2(
                salt = DsSigner.Salt.X4,
                query = query
            )

            val deviceId = authRepository.getOrCreateDeviceId()
            val deviceFp = deviceFpService.getOrCreateDeviceFp()

            val request = Request.Builder()
                .url(url)
                .addHeader("Cookie", cookie)
                .addHeader("DS", ds)
                .addHeader("User-Agent", DsSigner.USER_AGENT)
                .addHeader("Referer", "https://webstatic.mihoyo.com/")
                .addHeader("Origin", "https://webstatic.mihoyo.com")
                .addHeader("X-Requested-With", "com.mihoyo.hyperion")
                .addHeader("x-rpc-app_version", DsSigner.Salt.APP_VERSION)
                .addHeader("x-rpc-client_type", CLIENT_TYPE_WEB)
                .addHeader("x-rpc-device_id", deviceId)
                .addHeader("x-rpc-device_fp", deviceFp ?: "")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "getRoles")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "getRoles")
            }

            val listArray = json.getAsJsonObject("data")?.getAsJsonArray("list")
                ?: return@withContext ApiResult.Error("响应缺少 data.list", -1, respBody, "getRoles")

            val roles = mutableListOf<GameRole>()
            for (item in listArray) {
                val obj = item.asJsonObject
                val uid = obj.get("game_uid")?.asString ?: continue
                val region = obj.get("region")?.asString ?: "cn_gf01"
                val nickname = obj.get("nickname")?.asString ?: ""
                val level = obj.get("level")?.asInt ?: 0
                roles.add(GameRole(uid, region, nickname, level))
            }

            ApiResult.Success(roles)
        } catch (e: Exception) {
            ApiResult.Error("获取游戏角色异常: ${e.message}", -1, "", "getRoles")
        }
    }

    // ------------------------------------------------------------------
    // 5. 生成 authkey（DS1 + Cookie + LK2 salt）
    // ------------------------------------------------------------------
    suspend fun generateAuthKey(
        gameUid: String,
        region: String
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val cookie = authRepository.buildCookieString()
            if (cookie.isBlank()) {
                return@withContext ApiResult.Error("未登录", -1, "", "genAuthKey")
            }

            val requestBody = """
                {
                    "auth_appid": "webview_gacha",
                    "game_biz": "hk4e_cn",
                    "game_uid": "$gameUid",
                    "region": "$region"
                }
            """.trimIndent()

            val ds = DsSigner.generateDS1(salt = DsSigner.Salt.LK2)
            val deviceId = authRepository.getOrCreateDeviceId()
            val deviceFp = deviceFpService.getOrCreateDeviceFp()

            val request = Request.Builder()
                .url(API_GEN_AUTH_KEY)
                .addHeader("Cookie", cookie)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Referer", "https://webstatic.mihoyo.com")
                .addHeader("Origin", "https://webstatic.mihoyo.com")
                .addHeader("X-Requested-With", "com.mihoyo.hyperion")
                .addHeader("User-Agent", DsSigner.USER_AGENT)
                .addHeader("x-rpc-app_version", DsSigner.Salt.APP_VERSION)
                .addHeader("x-rpc-client_type", CLIENT_TYPE_WEB)
                .addHeader("x-rpc-device_id", deviceId)
                .addHeader("x-rpc-device_fp", deviceFp ?: "")
                .addHeader("DS", ds)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "genAuthKey")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "genAuthKey")
            }

            val authKey = json.getAsJsonObject("data")?.get("authkey")?.asString
                ?: return@withContext ApiResult.Error("响应缺少 data.authkey", -1, respBody, "genAuthKey")

            authRepository.cacheAuthKey(authKey)
            ApiResult.Success(authKey)
        } catch (e: Exception) {
            ApiResult.Error("生成 authkey 异常: ${e.message}", -1, "", "genAuthKey")
        }
    }

    /**
     * 获取有效的 authkey（优先缓存）
     */
    suspend fun getValidAuthKey(): ApiResult<String> {
        val cached = authRepository.getCachedAuthKeyIfFresh()
        if (!cached.isNullOrBlank()) {
            return ApiResult.Success(cached)
        }
        val uid = authRepository.getUid() ?: return ApiResult.Error("未设置 UID")
        val server = authRepository.getServer() ?: "cn_gf01"
        return generateAuthKey(uid, server)
    }
}
