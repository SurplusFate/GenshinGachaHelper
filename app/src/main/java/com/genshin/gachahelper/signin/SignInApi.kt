package com.genshin.gachahelper.signin

import com.genshin.gachahelper.auth.ApiResult
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.auth.DeviceFpService
import com.genshin.gachahelper.auth.DsSigner
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 米游社每日签到 API（原神 / 星穹铁道 / 绝区零通用 luna 系列）
 *
 * 使用网页端（client_type=5）请求体系，与现有 getGameRoles 保持一致：
 * - Referer / Origin: webstatic.mihoyo.com
 * - DS: DS2 + X6 salt（body/query 参与签名，与发送内容完全一致）
 * - Cookie: 复用 AuthRepository 保存的 stoken/mid 等全套凭证
 * - 额外头: x-rpc-signgame=hk4e 用于标记目标游戏
 *
 * 参考实现:
 * - UIGF-org/mihoyo-api-collect#1
 * - starudream/miyoushe-task (luna sign 接口)
 */
@Singleton
class SignInApi @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceFpService: DeviceFpService,
    private val client: OkHttpClient
) {

    companion object {
        private const val API_BASE = "https://api-takumi.mihoyo.com/event/luna"

        /** 原神国服每日签到活动 ID */
        const val GENSHIN_ACT_ID = "e202311201442471"

        /** 通用签名请求头（App 模式，与扫码登录的 stoken v1/cookie_token 匹配）
         *
         * 参考 starudream/miyoushe-task（社区实测可用）：
         * - client_type=2 + Referer=app.mihoyo.com，而非 web 端 webstatic
         * - 扫码登录获得的是 App 级 stoken，打 web 域接口会返回 -100 凭证无效
         * - DS2 + X6 salt（版本无关）保持不变
         */
        private fun commonHeaders(
            cookie: String,
            ds: String,
            deviceId: String,
            deviceFp: String
        ): Map<String, String> = buildMap {
            put("Cookie", cookie)
            put("DS", ds)
            put("User-Agent", DsSigner.USER_AGENT)
            put("Referer", "https://app.mihoyo.com")
            put("Origin", "https://app.mihoyo.com")
            put("X-Requested-With", "com.mihoyo.hyperion")
            put("x-rpc-app_version", DsSigner.Salt.APP_VERSION)
            put("x-rpc-client_type", "2")
            put("x-rpc-device_id", deviceId)
            put("x-rpc-device_name", "Xiaomi M2101K9C")
            put("x-rpc-device_model", "M2101K9C")
            put("x-rpc-sys_version", "13")
            put("x-rpc-channel", "miyousheluodi")
            put("x-rpc-signgame", "hk4e")
            if (deviceFp.isNotBlank()) put("x-rpc-device_fp", deviceFp)
        }
    }

    /**
     * 查询今日签到状态（是否已签到、本月累计天数）
     */
    suspend fun getSignInfo(uid: String, region: String): ApiResult<SignInfo> =
        withContext(Dispatchers.IO) {
            try {
                val cookie = authRepository.buildCookieString()
                if (cookie.isBlank()) {
                    return@withContext ApiResult.Error("未登录，缺少有效凭证", -1, "", "signInfo")
                }
                val query = "act_id=$GENSHIN_ACT_ID&region=$region&uid=$uid&lang=zh-cn"
                val url = "$API_BASE/info?$query"

                val ds = DsSigner.generateDS2(
                    salt = DsSigner.Salt.X6,
                    query = query
                )
                val deviceId = authRepository.getOrCreateDeviceId()
                val deviceFp = deviceFpService.getOrCreateDeviceFp()

                val request = Request.Builder()
                    .url(url)
                    .apply {
                        commonHeaders(cookie, ds, deviceId, deviceFp ?: "")
                            .forEach { (k, v) -> addHeader(k, v) }
                    }
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Error(
                        "HTTP ${response.code}", response.code, body, "signInfo"
                    )
                }
                val json = JsonParser.parseString(body).asJsonObject
                val retcode = json.get("retcode")?.asInt ?: -1
                if (retcode != 0) {
                    return@withContext ApiResult.Error(
                        json.get("message")?.asString ?: "未知错误",
                        retcode, body, "signInfo"
                    )
                }
                val data = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                val info = SignInfo(
                    totalSignDay = data?.get("total_sign_day")?.asInt ?: 0,
                    today = data?.get("today")?.asString ?: "",
                    isSign = data?.get("is_sign")?.asBoolean ?: false,
                    region = data?.get("region")?.asString ?: region
                )
                ApiResult.Success(info)
            } catch (e: Exception) {
                ApiResult.Error("查询签到状态异常: ${e.message}", -1, "", "signInfo")
            }
        }

    /**
     * 执行签到
     *
     * retcode=0 成功；-5003 表示今天已经签到过（社区约定，message 为中文提示）；
     * 返回 message 供直接展示给用户。
     */
    suspend fun sign(uid: String, region: String): ApiResult<SignResult> =
        withContext(Dispatchers.IO) {
            try {
                val cookie = authRepository.buildCookieString()
                if (cookie.isBlank()) {
                    return@withContext ApiResult.Error("未登录，缺少有效凭证", -1, "", "sign")
                }

                // body 字符串与 DS 签名使用同一份，保证服务端校验通过
                val bodyJson = JsonParser.parseString(
                    """{"act_id":"$GENSHIN_ACT_ID","region":"$region","uid":"$uid","lang":"zh-cn"}"""
                ).asJsonObject.toString()

                val ds = DsSigner.generateDS2(
                    salt = DsSigner.Salt.X6,
                    body = bodyJson
                )
                val deviceId = authRepository.getOrCreateDeviceId()
                val deviceFp = deviceFpService.getOrCreateDeviceFp()

                val request = Request.Builder()
                    .url("$API_BASE/sign")
                    .apply {
                        commonHeaders(cookie, ds, deviceId, deviceFp ?: "")
                            .forEach { (k, v) -> addHeader(k, v) }
                        addHeader("Content-Type", "application/json")
                        addHeader("Accept", "application/json, text/plain, */*")
                    }
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Error(
                        "HTTP ${response.code}", response.code, body, "sign"
                    )
                }
                val json = JsonParser.parseString(body).asJsonObject
                val retcode = json.get("retcode")?.asInt ?: -1
                val message = json.get("message")?.asString ?: "未知错误"
                val risk = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.let { it.get("is_risk")?.asBoolean } ?: false
                if (retcode == 0 || retcode == -5003) {
                    // -5003 = 今日已签到（服务端直接返回中文 message）
                    ApiResult.Success(
                        SignResult(
                            success = true,
                            alreadySigned = retcode == -5003,
                            message = message,
                            isRisk = risk
                        )
                    )
                } else {
                    ApiResult.Error(
                        message,
                        retcode, body, if (risk) "sign_risk" else "sign"
                    )
                }
            } catch (e: Exception) {
                ApiResult.Error("签到异常: ${e.message}", -1, "", "sign")
            }
        }
}

/** 今日签到状态 */
data class SignInfo(
    val totalSignDay: Int,
    val today: String,
    val isSign: Boolean,
    val region: String
)

/** 签到执行结果 */
data class SignResult(
    val success: Boolean,
    val alreadySigned: Boolean,
    val message: String,
    val isRisk: Boolean
)
