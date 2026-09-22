package com.genshin.gachahelper.auth

import com.google.gson.JsonObject
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
 *
 * 凭据来源：确认登录后服务端通过**响应头 Set-Cookie** 下发
 * （ltoken_v2 / cookie_token_v2 / ltuid_v2 / mid 等），响应体的
 * data.tokens 数组通常为空。body 解析仅作兼容回退。
 */
data class PassportQrStatus(
    val status: String,
    val stoken: String?,
    val mid: String?,
    val aid: String?,
    val ltoken: String? = null,
    val cookieToken: String? = null,
    val rawResponse: String,
    val setCookieHeader: String = "",
    /** data.tokens 数组的原始内容（诊断用：确认服务端是否下发凭据） */
    val tokensRaw: String = ""
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
    private val cookieExtractor: CookieExtractor,
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
        // 注：该兑换接口在官方并不存在，已移除；
        // H5 网页登录凭证（cookie_token_v2/ltoken_v2）可直接用于后续接口，无需兑换 stoken。

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
        const val CLIENT_TYPE_WEB = "5"
        const val CLIENT_TYPE_TOKEN = "4"
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
            AppLog.i(
                "Passport", "createQR",
                "POST url=$API_PASSPORT_CREATE_QR resp http=${response.code} " +
                    "trace=${response.header("x-trace-id") ?: "-"} " +
                    "body_len=${respBody.length} body_prefix=${respBody.take(200)}"
            )

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "passportCreate")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "passportCreate")
            }

            val data = json.getAsJsonObjectSafe("data")
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
                val setCookies = response.headers("Set-Cookie")
                AppLog.i(
                    "Passport", "queryQR",
                    "POST url=$API_PASSPORT_QUERY_QR ticket=${ticket.take(20)}... " +
                        "resp http=${response.code} body_len=${respBody.length} " +
                        "set_cookie_count=${setCookies.size} " +
                        "body_prefix=${respBody.take(220)}"
                )

                if (!response.isSuccessful) {
                    return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "passportQuery")
                }

                val json = JsonParser.parseString(respBody).asJsonObject
                val retcode = json.get("retcode")?.asInt ?: -1

                if (retcode != 0) {
                    val msg = json.get("message")?.asString ?: "未知错误"
                    return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "passportQuery")
                }

                val data = json.getAsJsonObjectSafe("data")
                    ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "passportQuery")

                val status = data.get("status")?.asString ?: "Created"

                var stoken: String? = null
                var mid: String? = null
                var aid: String? = null
                var ltoken: String? = null
                var cookieToken: String? = null

                if (status == "Confirmed") {
                    val userInfo = data.getAsJsonObjectSafe("user_info")
                    mid = userInfo?.get("mid")?.asStringSafe()
                    aid = userInfo?.get("aid")?.asStringSafe()
                        ?: userInfo?.get("uid")?.asStringSafe()
                        ?: userInfo?.get("account_id")?.asStringSafe()

                    // ── 凭据提取（2026-09 二次修正）─────────────────────
                    // 凭据可能来自两处，两处都要试，不能互相短路：
                    //  A) 响应头 Set-Cookie（web 流程下发
                    //     ltoken_v2 / cookie_token_v2 / ltuid_v2 / mid）
                    //  B) 响应体 data.tokens 数组（app 流程，元素形如
                    //     {"token":"...","token_type":N}，部分版本带 "name"）
                    //
                    // 参考实现 gsuid_core/cookie_manager/qrlogin.py：
                    // 从 data.tokens 按 name 取 stoken，取不到则**兜底取
                    // 第一个元素**（tokens[0]["token"]）；TeyvatGuide 的类型
                    // 定义里该元素只有 token + token_type（无 name）。
                    // 因此按 name → token_type → 首个 三级策略依次尝试。
                    // ──────────────────────────────────────────────
                    val setCookies = response.headers("Set-Cookie")
                    if (setCookies.isNotEmpty()) {
                        val extracted = cookieExtractor.extract(setCookies.joinToString("; "))
                        stoken = extracted.stoken
                        ltoken = extracted.ltoken
                        cookieToken = extracted.cookieToken
                        if (extracted.ltuid?.isNotBlank() == true) aid = extracted.ltuid
                        if (extracted.mid?.isNotBlank() == true) mid = extracted.mid
                    }

                    // body 的 tokens 数组：无条件解析（用于补齐 + 诊断）
                    val tokensArray = data.getAsJsonArraySafe("tokens")
                    if (tokensArray != null && tokensArray.size() > 0) {
                        var stokenNamed: String? = null
                        var ltokenV: String? = null
                        var cookieTokenV: String? = null
                        var firstToken: String? = null
                        for (tokenObj in tokensArray) {
                            if (!tokenObj.isJsonObject) continue
                            val obj = tokenObj.asJsonObject
                            val token = obj.get("token")?.asString ?: continue
                            if (firstToken.isNullOrBlank()) firstToken = token
                            val name = obj.get("name")?.asString.orEmpty()
                            when (name) {
                                "stoken_v2", "stoken" -> stokenNamed = token
                                "ltoken_v2", "ltoken" -> ltokenV = token
                                "cookie_token_v2", "cookie_token" -> cookieTokenV = token
                            }
                        }
                        // name 匹配不到时兜底取首个（与 gsuid_core 一致）
                        if (stoken.isNullOrBlank()) {
                            stoken = stokenNamed ?: firstToken
                        }
                        if (ltoken.isNullOrBlank()) ltoken = ltokenV
                        if (cookieToken.isNullOrBlank()) cookieToken = cookieTokenV
                    }
                }

                ApiResult.Success(
                    PassportQrStatus(
                        status = status,
                        stoken = stoken,
                        mid = mid,
                        aid = aid,
                        ltoken = ltoken,
                        cookieToken = cookieToken,
                        rawResponse = respBody,
                        setCookieHeader = response.headers("Set-Cookie").joinToString("\n"),
                        tokensRaw = data.get("tokens")?.toString().orEmpty()
                    ).also {
                        // 凭据提取诊断：把关键字段的存在性落到日志
                        // 用户最新反馈："扫码确认成功 stoken=null"——这里把空/有都标清楚
                        AppLog.i(
                            "Passport", "queryQR",
                            "Confirmed 凭据提取 stoken=${it.stoken.isNullOrBlank().let { b -> if (b) "<empty>" else "<present>" }} " +
                                "ltoken=${it.ltoken.isNullOrBlank().let { b -> if (b) "<empty>" else "<present>" }} " +
                                "cookieToken=${it.cookieToken.isNullOrBlank().let { b -> if (b) "<empty>" else "<present>" }} " +
                                "mid=${it.mid ?: "<empty>"} aid=${it.aid ?: "<empty>"} " +
                                "tokens_count=${if (it.tokensRaw.isBlank()) 0 else it.tokensRaw.length} " +
                                "set_cookie_lines=${it.setCookieHeader.lines().size}"
                        )
                    }
                )
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

            AppLog.i(
                "Token", "getCookieTokenByStoken",
                "GET url=${url.take(180)}... resp http=${response.code} " +
                    "trace=${response.header("x-trace-id") ?: "-"} " +
                    "body_len=${respBody.length} body_prefix=${respBody.take(200)}"
            )

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "getCookieToken")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "getCookieToken")
            }

            val data = json.getAsJsonObjectSafe("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "getCookieToken")

            val cookieToken = data.get("cookie_token")?.asString
                ?: return@withContext ApiResult.Error("响应缺少 cookie_token", -1, respBody, "getCookieToken")

            ApiResult.Success(cookieToken)
        } catch (e: Exception) {
            AppLog.e("Token", "getCookieTokenByStoken", "异常 ${e.javaClass.simpleName}: ${e.message}", e)
            ApiResult.Error("换取 cookie_token 异常: ${e.message}", -1, "", "getCookieToken")
        }
    }

    // ------------------------------------------------------------------
    // P3b. 用 stoken 换 ltoken（passport API，GET，无需 DS）
    // 参考 TeyvatGuide getLTokenBySToken
    // 2026-09-20 修复：POST 会返回 405 Method Not Allowed（真机日志实证），
    // 该接口实际是 GET（与 getCookieAccountInfoBySToken 同族）。
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
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            AppLog.i(
                "Token", "getLTokenByStoken",
                "GET url=$API_GET_LTOKEN_BY_STOKEN resp http=${response.code} " +
                    "trace=${response.header("x-trace-id") ?: "-"} " +
                    "body_len=${respBody.length} body_prefix=${respBody.take(200)}"
            )

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code, respBody, "getLToken")
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error("$msg (code: $retcode)", retcode, respBody, "getLToken")
            }

            val ltoken = json.getAsJsonObjectSafe("data")?.get("ltoken")?.asStringSafe()
                ?: return@withContext ApiResult.Error("响应缺少 ltoken", -1, respBody, "getLToken")

            ApiResult.Success(ltoken)
        } catch (e: Exception) {
            AppLog.e("Token", "getLTokenByStoken", "异常 ${e.javaClass.simpleName}: ${e.message}", e)
            ApiResult.Error("换取 ltoken 异常: ${e.message}", -1, "", "getLToken")
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

            val data = json.getAsJsonObjectSafe("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "fetch")

            val url = data.get("url")?.asStringSafe()
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

                val data = json.getAsJsonObjectSafe("data")
                    ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "query")

                val stat = data.get("stat")?.asString ?: "Init"
                var uid: String? = null
                var gameToken: String? = null

                if (stat == "Confirmed") {
                    val payload = data.getAsJsonObjectSafe("payload")
                    if (payload != null) {
                        val raw = payload.get("raw")?.asStringSafe()
                        if (!raw.isNullOrBlank()) {
                            try {
                                val rawJson = JsonParser.parseString(raw).asJsonObjectOrNullSafe()
                                uid = rawJson?.get("uid")?.asStringSafe()
                                gameToken = rawJson?.get("token")?.asStringSafe()
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

            val data = json.getAsJsonObjectSafe("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "getToken")

            val tokenObj = data.getAsJsonObjectSafe("token")
            val stoken = tokenObj?.get("token")?.asStringSafe()
                ?: return@withContext ApiResult.Error("响应缺少 token", -1, respBody, "getToken")

            val mid = data.getAsJsonObjectSafe("user_info")?.get("mid")?.asStringSafe() ?: ""

            ApiResult.Success(TokenInfo(stoken, mid))
        } catch (e: Exception) {
            ApiResult.Error("换取 stoken 异常: ${e.message}", -1, "", "getToken")
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

            val listArray = json.getAsJsonObjectSafe("data")?.getAsJsonArraySafe("list")
                ?: return@withContext ApiResult.Error("响应缺少 data.list", -1, respBody, "getRoles")

            val roles = mutableListOf<GameRole>()
            for (item in listArray) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val uid = obj.get("game_uid")?.asStringSafe() ?: continue
                val region = obj.get("region")?.asStringSafe() ?: "cn_gf01"
                val nickname = obj.get("nickname")?.asStringSafe() ?: ""
                val level = obj.get("level")?.asIntSafe() ?: 0
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

            val authKey = json.getAsJsonObjectSafe("data")?.get("authkey")?.asStringSafe()
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

// ==================== 每日便笺（实时状态：树脂 / 洞天宝钱） ====================

/**
 * 每日便笺（实时状态）数据
 *
 * 字段说明：
 * - 树脂：currentResin / maxResin，resinRecoverySeconds 为距离下一点树脂的剩余秒数（满时无意义）
 * - 洞天宝钱：currentHomeCoin / maxHomeCoin，homeCoinRecoverySeconds 为距离下一点宝钱的剩余秒数
 * - 每日委托：finishedTaskNum / totalTaskNum
 * - 周本减半：remainResinDiscount / resinDiscountLimit
 */
data class DailyNoteData(
    val currentResin: Int,
    val maxResin: Int,
    val resinRecoverySeconds: Long,
    val currentHomeCoin: Int,
    val maxHomeCoin: Int,
    val homeCoinRecoverySeconds: Long,
    val finishedTaskNum: Int,
    val totalTaskNum: Int,
    val remainResinDiscount: Int,
    val resinDiscountLimit: Int,
    /** 本次数据拉取时刻（用于倒计时本地推算，避免每秒请求服务器） */
    val fetchedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 树脂恢复速率：8 分钟 / 点（原神固定值，接口只给相位不给速率） */
        const val RESIN_SECONDS_PER_POINT = 480L

        /** 洞天宝钱恢复速率：1 小时 / 个（原神固定值） */
        const val HOME_COIN_SECONDS_PER_POINT = 3600L
    }

    val resinFull: Boolean get() = currentResin >= maxResin
    val homeCoinFull: Boolean get() = currentHomeCoin >= maxHomeCoin

    /**
     * [now] 时刻的树脂状态（纯本地推算，不请求服务器）。
     *
     * 每天只在首次启动时拉取一次快照，之后数值增长与倒计时全部由本方法
     * 基于快照 + 本地时钟外推，UI 每秒调用一次即可实现"自动更新"。
     */
    fun resinAt(now: Long = System.currentTimeMillis()): ResourceProjection =
        project(
            current = currentResin,
            max = maxResin,
            recoverySeconds = resinRecoverySeconds,
            secondsPerPoint = RESIN_SECONDS_PER_POINT,
            now = now
        )

    /** [now] 时刻的洞天宝钱状态（纯本地推算，不请求服务器） */
    fun homeCoinAt(now: Long = System.currentTimeMillis()): ResourceProjection =
        project(
            current = currentHomeCoin,
            max = maxHomeCoin,
            recoverySeconds = homeCoinRecoverySeconds,
            secondsPerPoint = HOME_COIN_SECONDS_PER_POINT,
            now = now
        )

    /**
     * 快照时刻起算的「距下一点恢复」剩余秒数（归一化口径与 [project] 一致）。
     *
     * 阈值提醒要算"还有多久涨到目标值"，必须与界面外推同源，
     * 否则会出现"界面已到 120、提醒却晚几分钟"的错位。
     */
    fun resinNextPointInSeconds(): Long =
        normalizeNextPoint(resinRecoverySeconds, RESIN_SECONDS_PER_POINT, currentResin, maxResin)

    /** 洞天宝钱快照时刻起算的「距下一点恢复」剩余秒数 */
    fun homeCoinNextPointInSeconds(): Long =
        normalizeNextPoint(homeCoinRecoverySeconds, HOME_COIN_SECONDS_PER_POINT, currentHomeCoin, maxHomeCoin)

    /**
     * 树脂推算值首次达到 [threshold] 的时刻（毫秒时间戳）。
     *
     * @return null 表示阈值不合法（≤0 或超过上限）、或已满且阈值更高；
     *         当前值已达标时返回 [now]（由调用方决定是否立即提醒）。
     */
    fun resinReachAt(threshold: Int, now: Long = System.currentTimeMillis()): Long? =
        reachAt(
            current = currentResin,
            max = maxResin,
            nextPointInSeconds = resinNextPointInSeconds(),
            secondsPerPoint = RESIN_SECONDS_PER_POINT,
            threshold = threshold,
            now = now
        )

    /** 洞天宝钱推算值首次达到 [threshold] 的时刻（毫秒时间戳） */
    fun homeCoinReachAt(threshold: Int, now: Long = System.currentTimeMillis()): Long? =
        reachAt(
            current = currentHomeCoin,
            max = maxHomeCoin,
            nextPointInSeconds = homeCoinNextPointInSeconds(),
            secondsPerPoint = HOME_COIN_SECONDS_PER_POINT,
            threshold = threshold,
            now = now
        )

    private fun reachAt(
        current: Int,
        max: Int,
        nextPointInSeconds: Long,
        secondsPerPoint: Long,
        threshold: Int,
        now: Long
    ): Long? {
        if (threshold <= 0 || threshold > max) return null
        // 已达标（含已满）：立即视为到点，是否真的提醒交给调用方的每日/防抖策略
        if (current >= threshold) return now
        if (current >= max) return null
        val periodMs = secondsPerPoint * 1000L
        val nextPointAt = fetchedAt + nextPointInSeconds * 1000L
        // 从 current 涨到 threshold 共 (threshold - current) 点，其中第 1 点即"下一点"，
        // 其余按固定单点周期顺延
        return nextPointAt + (threshold - current - 1) * periodMs
    }

    /**
     * 服务端 recovery 字段语义归一化（口径说明见 [project]）：
     * 统一为"距下一点恢复的剩余秒数"，并夹取到单点周期内。
     */
    private fun normalizeNextPoint(
        recoverySeconds: Long,
        secondsPerPoint: Long,
        current: Int,
        max: Int
    ): Long {
        if (current >= max) return 0L
        val pointsToFull = (max - current - 1).coerceAtLeast(0)
        val beforeLastPoint = pointsToFull * secondsPerPoint
        val rawNext = if (recoverySeconds > beforeLastPoint) {
            // 服务端给的是「距回满」
            recoverySeconds - beforeLastPoint
        } else {
            // 服务端给的是「距下一点」
            recoverySeconds
        }
        // 兜底夹取到单点周期内，抵御服务端给 0 / 异常大值造成的跳变
        return rawNext.coerceIn(0L, secondsPerPoint)
    }

    /**
     * 按固定单点速率外推当前值 / 距下一点 / 距回满。
     *
     * 服务端 recovery_time 字段语义在不同端点与版本间并不统一：既可能是
     * 「距下一点恢复的秒数」（≤ 单点周期），也可能是「距回满的秒数」。
     * 这里以未满时理论上「除最后一点外的回满耗时」(max-current-1)*rate
     * 作为分界做自适应识别，两种语义都能算对，避免换端点后倒计时翻倍或缩水。
     */
    private fun project(
        current: Int,
        max: Int,
        recoverySeconds: Long,
        secondsPerPoint: Long,
        now: Long
    ): ResourceProjection {
        if (current >= max) {
            return ResourceProjection(
                value = max,
                max = max,
                nextPointInSeconds = 0L,
                fullInSeconds = 0L
            )
        }

        // 单位统一：fetchedAt / now 是毫秒时间戳，而接口给的 recoverySeconds 与
        // 单点速率 secondsPerPoint 都是「秒」。2026-09-21 修复：此前把秒直接加到
        // 毫秒时间戳上参与运算，导致外推速度被放大 1000 倍
        // （树脂每 0.48 秒 +1 点、宝钱每 3.6 秒 +1 个，打开首页很快显示满值），
        // 这里全部换算成毫秒再运算。
        val periodMs = secondsPerPoint * 1000L
        val nextPointInSeconds = normalizeNextPoint(recoverySeconds, secondsPerPoint, current, max)
        val nextPointAt = fetchedAt + nextPointInSeconds * 1000L

        val gained = if (now >= nextPointAt) {
            (now - nextPointAt) / periodMs + 1L
        } else {
            0L
        }
        val value = (current + gained).coerceAtMost(max.toLong()).toInt()

        // 距下一点：走过整周期后要按周期取余回绕，不能一直钳在 0
        val nextIn = if (value >= max) {
            0L
        } else {
            val rem = ((now - nextPointAt) % periodMs + periodMs) % periodMs
            periodMs - rem
        }
        // 距回满：先补满"下一点"，其余按整周期顺延（(max-current-1) 个周期）
        val pointsToFull = (max - current - 1).coerceAtLeast(0)
        val fullIn = if (value >= max) {
            0L
        } else {
            (nextPointAt + pointsToFull * periodMs - now).coerceAtLeast(0L)
        }
        return ResourceProjection(
            value = value,
            max = max,
            nextPointInSeconds = nextIn,
            fullInSeconds = fullIn
        )
    }
}

/**
 * 单项资源的本地推算结果（由 [DailyNoteData] 快照 + 本地时钟外推得到）
 *
 * @param value              推算出的当前值
 * @param max                上限
 * @param nextPointInSeconds 距下一点恢复的剩余秒数（已满时为 0）
 * @param fullInSeconds      距回满的剩余秒数（已满时为 0）
 */
data class ResourceProjection(
    val value: Int,
    val max: Int,
    val nextPointInSeconds: Long,
    val fullInSeconds: Long
) {
    val isFull: Boolean get() = value >= max
}

/**
 * 每日便笺（原神实时状态）服务
 *
 * 接口：api-takumi-record.mihoyo.com/game_record/app/genshin/api/dailyNote
 * 鉴权：DS2 + 4X salt + Cookie（client_type=5），query 需参与签名
 * 限制：米游社对该接口有频率限制，客户端侧需自行节流（见 HomeViewModel 的 60 秒缓存窗口）
 *
 * ── 2026-09 修复说明 ──────────────────────────────────────────
 * 5003 = "缺少合法设备信息"（UIGF-org/mihoyo-api-collect#37）。
 * 根因是 DeviceFpService 旧版拿不到 getFp 签发的合法指纹，转而缓存了
 * 自造的 12 位随机串。本类新增自愈逻辑：遇 5003 先强制刷新设备指纹
 * （丢弃脏缓存、重新申请），再重试一次，避免用户被脏缓存永久卡死。
 *
 * 1034 = 触发米游社反机器人风控（geetest 人机验证）。
 * 5003 修复后指纹已合法，但新指纹无历史信誉，敏感接口首次访问容易
 * 被要求完成一次滑块验证。本类不做自动重试，而是把 1034 透传给 UI
 * 层（HomeViewModel）触发 [VerificationService] 的验证弹窗流程：
 * createVerification → WebView 滑块 → verifyVerification → 携带一次性
 * x-rpc-chellange 头重试本接口。
 * ──────────────────────────────────────────────────────────────
 */
@Singleton
class DailyNoteService @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceFpService: DeviceFpService,
    private val verificationService: VerificationService,
    private val client: OkHttpClient
) {
    companion object {
        private const val API_DAILY_NOTE =
            "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/dailyNote"
        private const val REFERER =
            "https://webstatic.mihoyo.com/app/community-game-records/index.html?v=6"
    }

    /**
     * 拉取实时便笺。
     * 遇 5003（设备验证未通过）时强制刷新设备指纹并重试一次：
     * 历史版本可能把自造的非法指纹写进缓存且永不失效，这里兜底自愈。
     */
    suspend fun fetchDailyNote(): ApiResult<DailyNoteData> {
        val first = fetchDailyNoteOnce()
        if (first is ApiResult.Error && first.code == 5003) {
            deviceFpService.refreshDeviceFp()
            return fetchDailyNoteOnce()
        }
        return first
    }

    private suspend fun fetchDailyNoteOnce(): ApiResult<DailyNoteData> = withContext(Dispatchers.IO) {
        try {
            val baseCookie = authRepository.buildCookieString()
            if (baseCookie.isBlank()) {
                return@withContext ApiResult.Error("未登录，缺少有效凭证", -1, "", "dailyNote")
            }

            val roleId = authRepository.getUid()
            if (roleId.isNullOrBlank()) {
                return@withContext ApiResult.Error("未获取到角色 UID", -1, "", "dailyNote")
            }
            val server = authRepository.getServer().orEmpty().ifBlank { "cn_gf01" }

            // query 参与签名，键按字典序拼接（role_id < server）
            val query = "role_id=$roleId&server=$server"
            val url = "$API_DAILY_NOTE?$query"

            // 2026-09-20 v2：缝合形态（hybrid）——与 create/verify 统一（除
            // challenge_game 为验证接口专有头外，业务请求头集与其余完全一致）。
            // 真机天然 A/B 铁证：全量 Cookie + 设备头形态 → dailyNote 1034
            // （设备验证层通过）；official-form 纯浏览器形态 → 5003（OkHttp TLS
            // 冒充 Chrome UA 被识破）。一次性 challenge token 只在"同一客户端
            // 身份"下有效，验证链路（create/verify/dailyNote）三处形态必须一致。
            val cookie = authRepository.buildCookieWithDeviceFp(baseCookie)
            val deviceId = authRepository.getOrCreateDeviceId()
            val deviceFp = deviceFpService.getOrCreateDeviceFp()
            val ds = DsSigner.generateDS2(salt = DsSigner.Salt.X4, query = query)

            // 一次性 challenge token（风控验证通过后获得）：取出即清，只对本次请求生效
            val challengeToken = verificationService.getAndClearChallengeToken(roleId)
            AppLog.i(
                "DailyNote", "fetch",
                "GET url=$url roleId=$roleId ds=$ds " +
                    "fp(last4)=${deviceFp?.takeLast(4)} " +
                    "form=hybrid-v2 challenge_token_used=${!challengeToken.isNullOrBlank()}"
            )

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", VerificationService.USER_AGENT_VERIFY)
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Referer", REFERER)
                .addHeader("Origin", "https://webstatic.mihoyo.com")
                .addHeader("X-Requested-With", "com.mihoyo.hyperion")
                .addHeader("sec-fetch-dest", "empty")
                .addHeader("sec-fetch-site", "same-site")
                .addHeader("Cookie", cookie)
                .addHeader("DS", ds)
                .addHeader("x-rpc-app_version", VerificationService.APP_VERSION_VERIFY)
                .addHeader("x-rpc-client_type", MihoyoApiService.CLIENT_TYPE_WEB)
                .addHeader("x-rpc-device_name", VerificationService.DEVICE_NAME_VERIFY)
                .addHeader("x-rpc-language", "zh-cn")
                .addHeader("x-rpc-page", VerificationService.RPC_PAGE)
                .addHeader("x-rpc-sys_version", VerificationService.SYS_VERSION_VERIFY)
                .addHeader("x-rpc-tool_version", VerificationService.TOOL_VERSION)
            if (!deviceFp.isNullOrBlank()) {
                requestBuilder.addHeader("x-rpc-device_fp", deviceFp)
            }
            if (deviceId.isNotBlank()) {
                requestBuilder.addHeader("x-rpc-device_id", deviceId)
            }

            // 1034 风控验证通过后的一次性放行令牌。
            // 2026-09-20 修正头名：官方 bundle 实证为 x-rpc-challenge（正拼），
            // 旧名 x-rpc-chellange（拼错版）沿自 2022 年 paimon-webext，已过时。
            if (!challengeToken.isNullOrBlank()) {
                requestBuilder.addHeader("x-rpc-challenge", challengeToken)
            }

            val request = requestBuilder.get().build()

            val response = client.newCall(request).execute()

            // 记录 trace-id：若随后触发 1034 风控，验证请求需通过
            // x-rpc-challenge_trace 回传它来关联被拦截的请求链
            val traceId = response.header("x-trace-id")
            verificationService.saveTraceId(roleId, traceId)

            val respBody = response.body?.string() ?: ""

            AppLog.i(
                "DailyNote", "fetch",
                "resp http=${response.code} ct=${response.header("content-type") ?: "-"} " +
                    "trace=${traceId ?: "-"} " +
                    "body_len=${respBody.length} body_prefix=${respBody.take(220)}"
            )

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error(
                    "HTTP ${response.code}", response.code, respBody, "dailyNote"
                )
            }

            val json = JsonParser.parseString(respBody).asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                val hint = when (retcode) {
                    -100 -> "登录凭证已过期，请重新登录"
                    10101 -> "访问过于频繁或账号角色信息异常，请稍后再试"
                    10102 -> "该账号未公开游戏数据"
                    -10002 -> "该 Cookie 未绑定原神角色"
                    -110, -10001 -> "请求过于频繁，请稍后再试"
                    5003 -> "设备验证未通过（风控拦截），已自动刷新设备指纹｜fpSource:${deviceFpService.lastFpSource} getFp:${deviceFpService.lastGetFpStatus}"
                    1034 -> "触发米游社安全验证，请完成滑块验证后重试"
                    -3503 -> "当前设备或网络环境存在风险，请稍后重试"
                    -502 -> "请求参数有误，请检查角色信息"
                    else -> msg
                }
                AppLog.w(
                    "DailyNote", "fetch",
                    "非 0 响应 retcode=$retcode msg=$msg hint=$hint trace=$traceId"
                )
                return@withContext ApiResult.Error(
                    "$hint (code: $retcode)", retcode, respBody, "dailyNote"
                )
            }

            val data = json.getAsJsonObjectSafe("data")
                ?: return@withContext ApiResult.Error("响应缺少 data", -1, respBody, "dailyNote")

            ApiResult.Success(
                DailyNoteData(
                    currentResin = data.intOf("current_resin"),
                    maxResin = data.intOf("max_resin", 200),
                    resinRecoverySeconds = data.longOf("resin_recovery_time"),
                    currentHomeCoin = data.intOf("current_home_coin"),
                    maxHomeCoin = data.intOf("max_home_coin", 2400),
                    homeCoinRecoverySeconds = data.longOf("home_coin_recovery_time"),
                    finishedTaskNum = data.intOf("finished_task_num"),
                    totalTaskNum = data.intOf("total_task_num", 4),
                    remainResinDiscount = data.intOf("remain_resin_discount_num"),
                    resinDiscountLimit = data.intOf("resin_discount_num_limit", 3)
                )
            )
        } catch (e: Exception) {
            ApiResult.Error("获取每日便笺异常: ${e.message}", -1, "", "dailyNote")
        }
    }
}

/** 容错取 Int：兼容服务端把数字返回成字符串的情况 */
private fun JsonObject.intOf(key: String, default: Int = 0): Int {
    val element = get(key) ?: return default
    return element.asString.trim().toIntOrNull() ?: default
}

/** 容错取 Long：重置倒计时为秒数字符串，空串/异常时返回 0 */
private fun JsonObject.longOf(key: String): Long {
    val element = get(key) ?: return 0L
    return element.asString.trim().toLongOrNull() ?: 0L
}
