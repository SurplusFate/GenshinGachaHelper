package com.genshin.gachahelper.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * geetest 验证码参数（createVerification 响应）
 * 传给前端（WebView 内 gt.js）初始化滑块组件
 */
data class CaptchaData(
    val gt: String,
    val challenge: String,
    val newCaptcha: Boolean
)

/**
 * 用户完成验证后提交的 geetest 结果。
 *
 * 米游社 1034 风控可能下发两种极验验证：
 * - **v3**（滑块）：geetest_challenge / geetest_validate / geetest_seccode
 * - **v4**（点选图形）：captcha_id / lot_number / pass_token / gen_time / captcha_output
 *
 * 两者字段完全不同，写死 v3 字段提交 v4 结果会被服务端判定无效
 * （实测表现为 {"data":null,"message":"","retcode":-1}）。
 * 因此这里保留原始 JSON 对象，由服务端版本自动适配提交体。
 */
data class GeetestResult(
    val raw: JsonObject
) {
    /** 是否是 v4 结果（含 lot_number 字段） */
    val isV4: Boolean get() = raw.has("lot_number")

    /** v4 提交体：服务端要求 4 个字段 */
    fun toV4Body(): String = JsonObject().apply {
        raw.get("lot_number")?.let { add("lot_number", it) }
        raw.get("captcha_output")?.let { add("captcha_output", it) }
        raw.get("pass_token")?.let { add("pass_token", it) }
        raw.get("gen_time")?.let { add("gen_time", it) }
    }.toString()

    /** v3 提交体：服务端要求 3 个字段 */
    fun toV3Body(): String = JsonObject().apply {
        raw.get("geetest_challenge")?.let { add("geetest_challenge", it) }
        raw.get("geetest_validate")?.let { add("geetest_validate", it) }
        // 极验 v3 的 seccode 固定为 "<validate>|jordan"。
        // 老版 gt.js（0.4.9）的 getValidate() 返回的 geetest_seccode 往往只有
        // validate 本身、缺少 "|jordan" 后缀（实测两者字符串完全相同），
        // 直接提交会被极验二次校验判为无效 → 服务端返回空 message 的
        // {"data":null,"message":"","retcode":-1}。这里统一补全后缀。
        raw.get("geetest_validate")?.asString?.let { v ->
            val sec = raw.get("geetest_seccode")?.asString.orEmpty()
            val fixed = if (sec.contains('|')) sec else "$v|jordan"
            addProperty("geetest_seccode", fixed)
        }
    }.toString()

    /** 提交体（自动按版本选择） */
    fun toRequestBody(): String = if (isV4) toV4Body() else toV3Body()

    /**
     * 备用提交体：v3 场景下使用 WebView 回传的**原始** seccode（不补 |jordan）。
     *
     * 不同版本 gt.js 对 seccode 的处理不一致：新版会自行拼上 "|jordan"，
     * 老版则原样返回。无法从字段值可靠判断，因此首次提交失败时用它兜底重试。
     * v4 无此问题，直接返回主提交体。
     */
    fun toFallbackBody(): String = if (isV4) toV4Body() else JsonObject().apply {
        raw.get("geetest_challenge")?.let { add("geetest_challenge", it) }
        raw.get("geetest_validate")?.let { add("geetest_validate", it) }
        raw.get("geetest_seccode")?.let { add("geetest_seccode", it) }
    }.toString()

    /** 是否为可识别的有效结果 */
    val isValid: Boolean
        get() = if (isV4) {
            raw.has("lot_number") && raw.has("pass_token")
        } else {
            raw.has("geetest_challenge") && raw.has("geetest_validate")
        }
}

/**
 * 米游社风控验证服务（retcode 1034 处理）
 *
 * 背景：实时便笺等接口被判定为风险流量时返回 1034，要求完成人机验证。
 * 流程（参考 daidr/paimon-webext 的线上实现）：
 *
 * 1. [createVerification]：GET card/wapi/createVerification?is_high=true
 *    → 返回 geetest 参数（gt / challenge / new_captcha）
 * 2. 前端 WebView 加载 gt.js 渲染验证组件，用户完成后拿到
 *    geetest_challenge / geetest_validate / geetest_seccode
 * 3. [verifyVerification]：POST card/wapi/verifyVerification 提交三元组
 *    → 返回一次性 challenge token（缓存，单次使用）
 * 4. 重试业务接口时带上 x-rpc-chellange 头（注意：米游社服务端就是
 *    这个拼错的头名，chellange ≠ challenge），token 用后即清
 *
 * 请求头一致性很关键：两个验证接口都必须带齐 Hyperion 客户端的
 * x-rpc-* 头（app_version / client_type / device_id / sys_version /
 * device_name / tool_version / page / language），缺任何一个都会被
 * 服务端拒绝。参考实现中 verifyVerification **不**携带
 * x-rpc-device_fp，本实现同样只在 createVerification 携带。
 *
 * trace-id 链路：每次 dailyNote 响应头 x-trace-id 记录下来，验证请求
 * 通过 x-rpc-challenge_trace 回传，帮助服务端关联被拦截的那次请求。
 */
@Singleton
class VerificationService @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceFpService: DeviceFpService,
    private val client: OkHttpClient
) {
    companion object {
        private const val API_CREATE_VERIFICATION =
            "https://api-takumi-record.mihoyo.com/game_record/app/card/wapi/createVerification?is_high=true"
        private const val API_VERIFY_VERIFICATION =
            "https://api-takumi-record.mihoyo.com/game_record/app/card/wapi/verifyVerification"

        /**
         * 告知服务端"本次验证是为了放行哪个接口"。
         * 与参考实现（paimon-webext）保持一致，经线上验证可用。
         */
        private const val CHALLENGE_PATH =
            "https://api-takumi-record.mihoyo.com/game_record/genshin/api/dailyNote"

        /**
         * 米游社 H5 工具身份常量。
         *
         * 这些值同时被 [DailyNoteService] 复用——风控验证发放的一次性 token
         * 只在"同一客户端身份"下有效，业务请求若用另一套身份（如 Android
         * 真机信息 + Android WebView UA）重试，会被服务端判定 token 无效，
         * 表现为验证通过后仍然 1034。因此统一对外暴露，保证两处完全一致。
         */
        const val TOOL_VERSION = "v5.0.1-ys"
        const val RPC_PAGE = "v5.0.1-ys_#/ys/daily"
        const val APP_VERSION_VERIFY = "2.75.1"
        const val SYS_VERSION_VERIFY = "17.1"
        const val DEVICE_NAME_VERIFY = "iPhone"

        /**
         * 验证接口专用 User-Agent。
         *
         * 米游社的 card/wapi 验证接口服务于 **H5 网页版记录工具**
         * （x-rpc-tool_version=v5.0.1-ys / x-rpc-page=v5.0.1-ys_#/ys/daily）。
         * 若同时带上 Android 机型信息，UA 与工具标识自相矛盾，
         * 会被风控判定为伪造请求（表现为 retcode -1 且 message 为空）。
         * 这里统一使用与 H5 工具一致的桌面 miHoYoBBS 标识。
         */
        const val USER_AGENT_VERIFY =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) miHoYoBBS/$APP_VERSION_VERIFY"

        // ===== 2026-09-20 终极定罪：官方 H5 浏览器形态（逆向官方 bundle 实证）=====
        //
        // 逆向米哈游官方原神战绩 H5 工具页（webstatic.mihoyo.com/app/community-game-records/
        // index.html → bundle_47f04af664b6146b68cc.js）的验证插件与请求封装：
        //   - 模块 1220（公共头）：浏览器形态 {"x-rpc-client_type":5, "x-rpc-app_version":"2.3.0",
        //     "DS": md5("h8w582wxwgqvahcdkpvdhbh2w9casgfl")}——DS 是固定哈希，非 DS2 签名
        //   - 验证插件（Ci）：create/verify 均携带 x-rpc-challenge_game: $getGameId()
        //   - 模块 14：$getGameId() = 2（原神游戏 ID）
        //   - 官方请求**不带** device_id/device_fp/device_name/sys_version/tool_version/
        //     page/X-Requested-With——那是 App WebView/原生形态的头
        //
        // 沙箱对照实验（lab2_official.py）铁证：
        //   A. 官方形态（challenge_game:2 + 固定DS + app_version 2.3.0）→ retcode 10306
        //      （业务层错误=假三元组被极验正确拒绝，**请求已穿透风控层**）
        //   B. 官方形态去掉 challenge_game → -1 空 message（=真机三轮结局）
        //   C. 原缝合形态（X4 DS2 + 21 头） → -1 空 message
        // ⇒ verify 被静默拒绝的真凶：缺失 x-rpc-challenge_game 头。
        const val OFFICIAL_APP_VERSION = "2.3.0"
        const val OFFICIAL_DS = "b2dc7990c30176dbd163281dbedc214a"
        const val CHALLENGE_GAME_GENSHIN = "2"
        const val USER_AGENT_BROWSER =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // ===== 2026-09-20 v2 修正：纯浏览器形态在真机触发 5003，回退缝合形态+补真凶头 =====
        //
        // official-form 版真机实测（10:01 日志）：dailyNote 返回 **5003（设备验证未通过）**
        // ——比 1034 更深的拒绝层。同一设备同 IP 37 秒内的天然 A/B：
        //   - 旧版缝合形态（全量 Cookie 含 DEVICEFP + x-rpc-device_fp 头 + miHoYoBBS UA）
        //     → dailyNote **1034**（设备验证层通过，业务层要求人机验证）
        //   - official-form（slim Cookie 无设备上下文 + 纯 Chrome UA + 无设备头）
        //     → dailyNote **5003**（设备验证层直接拒）
        // 根因：官方 bundle 的设备头集 ["x-rpc-device_id","x-rpc-device_fp",
        // "x-rpc-device_name","x-rpc-sys_version"] 是 dora 框架**双形态**设计——在米游社
        // App WebView 内打开时经 postMessage2App 带上，纯浏览器才不带。我们伪装纯浏览器，
        // 但 OkHttp 的 TLS 指纹不是 Chrome，被设备验证层识破。
        //
        // 沙箱 lab3 复核（W 系列）：
        //   W1. 缝合形态（旧版 21 头）+ challenge_game → verify **10306（穿透风控层）**
        //   W0. 缝合形态，无 challenge_game → -1（复现旧版失败）
        //   V.  官方形态 + DEVICEFP 三件套 cookie → verify 10306（加设备 cookie 无害）
        // ⇒ **v2 = 旧版缝合形态（设备层已证可通过）+ x-rpc-challenge_game（风控层真凶头）**
        //   最小 diff：与旧版唯一的区别就是多带一个 challenge_game 头。

    }

    /** 按 uid 缓存的一次性 challenge token（验证通过后获得，单次使用） */
    private val challengeTokens = ConcurrentHashMap<String, String>()

    /** 最近一次业务响应的 x-trace-id（验证请求需通过 challenge_trace 回传） */
    private val traceIds = ConcurrentHashMap<String, String>()

    /**
     * 最近一次 createVerification 响应的 x-trace-id。
     * 2026-09-20 实验 EXP-1：现行 verify 带的是 dailyNote 的 trace，
     * 但验证链 dailyNote→create→verify 的每步响应都会返回新 trace——
     * verify 或许应续 create 的 trace 才能通过服务端链路校验。
     */
    private val createTraces = ConcurrentHashMap<String, String>()

    /** 记录业务响应的 trace-id，供验证请求关联请求链 */
    fun saveTraceId(uid: String, traceId: String?) {
        if (uid.isNotBlank() && !traceId.isNullOrBlank()) {
            traceIds[uid] = traceId
        }
    }

    /**
     * 取出并清除一次性 challenge token（单次使用语义）。
     * 业务请求发起前调用：有 token 则带 x-rpc-chellange 头，无论本次
     * 请求成败 token 都视为已消费。
     */
    fun getAndClearChallengeToken(uid: String): String? =
        if (uid.isBlank()) null else challengeTokens.remove(uid)

    /**
     * 创建风控验证：向米哈游申请 geetest 验证参数。
     * 成功后由 UI 层弹 WebView 渲染验证组件。
     */
    suspend fun createVerification(): ApiResult<CaptchaData> = withContext(Dispatchers.IO) {
        try {
            val baseCookie = authRepository.buildCookieString()
            if (baseCookie.isBlank()) {
                AppLog.w("Verify", "createVerification", "未登录，缺少凭证，跳过")
                return@withContext ApiResult.Error("未登录，缺少有效凭证", -1, "", "createVerification")
            }
            val uid = authRepository.getUid().orEmpty()
            // 2026-09-20 v2：缝合形态（hybrid）——全量 Cookie（含 DEVICEFP 三件套），
            // 真机已证该形态可过设备验证层（旧版 dailyNote 1034 而非 5003）。
            val cookie = authRepository.buildCookieWithDeviceFp(baseCookie)
            val deviceId = authRepository.getOrCreateDeviceId()
            val deviceFp = deviceFpService.getOrCreateDeviceFp()
            val ds = DsSigner.generateDS2(salt = DsSigner.Salt.X4, query = "is_high=true")

            val builder = Request.Builder()
                .url(API_CREATE_VERIFICATION)
                .addHybridHeaders(cookie, ds, uid, deviceId, deviceFp.orEmpty())
            AppLog.i(
                "Verify", "createVerification",
                "req url=${API_CREATE_VERIFICATION} query=is_high=true " +
                    "uid=$uid cookie=full form=hybrid-v2 " +
                    "deviceId=$deviceId deviceFp(last4)=${deviceFp?.takeLast(4)} " +
                    "fpSource=${deviceFpService.lastFpSource} " +
                    "getFp=${deviceFpService.lastGetFpStatus} ds=$ds"
            )
            // 2026-09-19 补充埋点：create 侧头清单，用于与 verify 侧逐项 diff
            // （专项排查「create 成功 / verify 被拒」的头差异）
            run {
                val probe = builder.build()
                AppLog.i(
                    "Verify", "createHeaders",
                    buildString {
                        append("headers_count=").append(probe.headers.size).append(' ')
                        probe.headers.names().sorted().forEach { n ->
                            append('[').append(n).append('=')
                                .append(probe.header(n).orEmpty()).append("] ")
                        }
                    }
                )
            }

            val response = client.newCall(builder.get().build()).execute()
            val respBody = response.body?.string() ?: ""
            val hdr = "http=${response.code} ct=${response.header("content-type") ?: "-"} " +
                "trace=${response.header("x-trace-id") ?: "-"} " +
                "server=${response.header("server") ?: "-"}"
            AppLog.i(
                "Verify", "createVerification",
                "resp $hdr body_len=${respBody.length} body_prefix=${respBody.take(180)}"
            )
            // 2026-09-20：记录 create 响应 trace，供 verify 实验 EXP-1（链式续 trace 假设）
            response.header("x-trace-id")?.takeIf { it.isNotBlank() && uid.isNotBlank() }?.let {
                createTraces[uid] = it
            }

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error(
                    "HTTP ${response.code}", response.code, respBody, "createVerification"
                )
            }

            val parsed = if (respBody.isBlank()) {
                null
            } else {
                runCatching { JsonParser.parseString(respBody) }.getOrNull()
            }
            if (parsed == null || !parsed.isJsonObject) {
                return@withContext ApiResult.Error(
                    "服务端返回空响应/非 JSON（HTTP ${response.code}）",
                    response.code, respBody, "createVerification"
                )
            }

            val json = parsed.asJsonObject
            val retcode = json.get("retcode")?.asInt ?: -1
            if (retcode != 0) {
                val msg = json.get("message")?.asString ?: "未知错误"
                return@withContext ApiResult.Error(
                    "$msg (code: $retcode)", retcode, respBody, "createVerification"
                )
            }

            val data = json.getAsJsonObjectSafe("data")
                ?: return@withContext ApiResult.Error(
                    "响应缺少 data（data 字段为 null 或非对象）", -1, respBody, "createVerification"
                )
            val gt = data.get("gt")?.asStringSafe()
            val challenge = data.get("challenge")?.asStringSafe()
            if (gt.isNullOrBlank() || challenge.isNullOrBlank()) {
                return@withContext ApiResult.Error(
                    "验证参数缺失（gt/challenge）", -1, respBody, "createVerification"
                )
            }

            ApiResult.Success(
                CaptchaData(
                    gt = gt,
                    challenge = challenge,
                    newCaptcha = data.get("new_captcha")?.asBooleanSafe() ?: true
                )
            )
        } catch (e: Exception) {
            ApiResult.Error(
                "创建安全验证异常: ${e.javaClass.simpleName} ${e.message}", -1, "", "createVerification"
            )
        }
    }

    /**
     * 提交 geetest 验证结果，换取一次性 challenge token。
     * 成功后：缓存 token 并重新申请设备指纹（降低再次触发风控的概率）。
     */
    suspend fun verifyVerification(geetest: GeetestResult): ApiResult<String> {
        return withContext<ApiResult<String>>(Dispatchers.IO) {
            // 把"诊断上下文"做成可变变量，catch 时回填到 rawResponse
            // 用于应对 ClassCastException / IOException 等场景：服务端实际有响应，
            // 但代码路径走不到正常的解析逻辑——这种情况需要把响应头信息留下来诊断
            var lastHttpCode = -1
            var lastContentType = ""
            var lastTraceId = ""
            var lastServer = ""
            try {
                val baseCookie = authRepository.buildCookieString()
                if (baseCookie.isBlank()) {
                    AppLog.w("Verify", "verifyVerification", "未登录，缺少凭证，跳过")
                    return@withContext ApiResult.Error("未登录，缺少有效凭证", -1, "", "verifyVerification")
                }
                val uid = authRepository.getUid().orEmpty()
                // 2026-09-20 v2：基线 = 缝合形态（hybrid）+ challenge_game。
                // - fullCookie（基线）：全量 Cookie 含 DEVICEFP 三件套（真机已证设备层可通过）
                // - slimCookie：精简 H5 Cookie（实验变体用）
                // 沙箱 W 系列实验：缝合+challenge_game → 10306（穿透）；
                // 缝合无 challenge_game → -1（=旧版真机失败原因）。
                val slimCookie = authRepository.buildSlimBrowserCookie(baseCookie)
                val fullCookie = authRepository.buildCookieWithDeviceFp(baseCookie)
                val deviceId = authRepository.getOrCreateDeviceId()
                val deviceFp = deviceFpService.getOrCreateDeviceFp()

                if (!geetest.isValid) {
                    AppLog.w(
                        "Verify", "verifyVerification",
                        "无效结果 ver=${if (geetest.isV4) "v4" else "v3"} raw=${geetest.raw}"
                    )
                    return@withContext ApiResult.Error(
                        "验证结果字段不完整（${if (geetest.isV4) "v4" else "v3"}）：" +
                            geetest.raw.toString().take(160),
                        -1,
                        geetest.raw.toString(),
                        "verifyVerification"
                    )
                }

                // body 按极验版本自动适配：v3 用 geetest_* 三字段，
                // v4 用 lot_number/pass_token/gen_time/captcha_output 四字段。
                // 交给 JSON 库序列化（seccode 含竖线，手工拼接会破坏结构）
                //
                // seccode 双格式重试（2026-09 修复）：
                // 极验 v3 约定 geetest_seccode = "<validate>|jordan"，但老版
                // gt.js（0.4.9）的 getValidate() 常直接回传不带后缀的 seccode
                // （实测 validate 与 seccode 字符串完全相同）。无法从字段值
                // 可靠区分两种情形，因此先按补全后缀提交，若服务端仍拒绝
                // （空 message 的 -1，即极验二次校验不通过），再用 WebView
                // 回传的原始 seccode 重试一次，确保两种情况都能覆盖。
                val primaryBody = geetest.toRequestBody()
                val fallbackBody = geetest.toFallbackBody()

                AppLog.i(
                    "Verify", "verifyVerification",
                    "ver=${if (geetest.isV4) "v4" else "v3"} uid=$uid " +
                        "primary_body=$primaryBody " +
                        "fallback_differs=${fallbackBody != primaryBody}"
                )

                /**
                 * 2026-09-20 真机 A/B 形态实验（第三版，基线=缝合形态+challenge_game）：
                 * 基线若仍被拒，自动依次尝试 3 个兜底变体：
                 * - EXP-1 officialBrowser：纯官方浏览器形态（沙箱已证 10306 穿透；
                 *   真机若 5003 才是设备层问题，此变体在 verify 上或许可用）
                 * - EXP-2 cookie=slim：缝合头但 Cookie 换精简 H5（定位 Cookie 因素）
                 * - EXP-3 app_version=2.79.0：缝合头但版本换米游社 App 版本
                 */
                suspend fun runVerifyExperiments(): ApiResult<String>? {
                    val experiments = listOf(
                        VerifyExperiment(1, "officialBrowser", useOfficialBrowser = true),
                        VerifyExperiment(2, "cookie=slim", useSlimCookie = true),
                        VerifyExperiment(3, "app_version=2.79.0", appVersion = "2.79.0")
                    )
                    for (exp in experiments) {
                        delay(600)
                        AppLog.i("Verify", "verifyExperiment", "start exp=${exp.id} desc=${exp.desc}")
                        val out = try {
                            submitVerification(
                                primaryBody, slimCookie, fullCookie, uid, deviceId, deviceFp, exp
                            )
                        } catch (e: Exception) {
                            AppLog.e(
                                "Verify", "verifyExperiment",
                                "exp=${exp.id} 异常 ${e.javaClass.simpleName}: ${e.message}"
                            )
                            null
                        } ?: continue
                        lastHttpCode = out.lastHttpCode
                        lastContentType = out.lastContentType
                        lastTraceId = out.lastTraceId
                        lastServer = out.lastServer
                        if (out.retcode == 0) {
                            AppLog.i(
                                "Verify", "verifyExperiment",
                                "exp=${exp.id} 【成功】desc=${exp.desc} —— 该形态被服务端接受！"
                            )
                            return cacheTokenAndRefresh(out, uid)
                        }
                        AppLog.w(
                            "Verify", "verifyExperiment",
                            "exp=${exp.id} 被拒 rc=${out.retcode} msg=${out.message} " +
                                "raw=${out.raw.take(120)}"
                        )
                    }
                    AppLog.w(
                        "Verify", "verifyExperiment",
                        "缝合基线 + 3 个变体均被拒——参考 10306（极验校验失败，三元组问题）" +
                            "与 -1（风控层静默拒）的区别定位卡点"
                    )
                    return null
                }

                val primary = submitVerification(
                    primaryBody, slimCookie, fullCookie, uid, deviceId, deviceFp
                )
                // 捕获最近一次响应的头信息，便于 catch 时回填
                lastHttpCode = primary.lastHttpCode
                lastContentType = primary.lastContentType
                lastTraceId = primary.lastTraceId
                lastServer = primary.lastServer

                if (primary.retcode == 0) {
                    return@withContext cacheTokenAndRefresh(primary, uid)
                }

                if (fallbackBody != primaryBody) {
                    AppLog.w(
                        "Verify", "verifyVerification",
                        "primary 失败 (rc=${primary.retcode})，fallback 重试：" +
                            "fallback_body=$fallbackBody"
                    )
                    val retry = submitVerification(
                        fallbackBody, slimCookie, fullCookie, uid, deviceId, deviceFp
                    )
                    lastHttpCode = retry.lastHttpCode
                    lastContentType = retry.lastContentType
                    lastTraceId = retry.lastTraceId
                    lastServer = retry.lastServer
                    if (retry.retcode == 0) {
                        return@withContext cacheTokenAndRefresh(retry, uid)
                    }
                    // 两种格式都失败：先跑形态实验（一次人机验证收集多个数据点）
                    runVerifyExperiments()?.let { return@withContext it }
                    AppLog.e(
                        "Verify", "verifyVerification",
                        "primary+fallback 都失败 rc_primary=${primary.retcode} rc_fallback=${retry.retcode}"
                    )
                    return@withContext ApiResult.Error(
                        "${primary.message} (code: ${primary.retcode})",
                        primary.retcode,
                        "${primary.raw}\n[重试原始seccode]${retry.raw}",
                        "verifyVerification"
                    )
                }

                // primary 失败且无 fallback 可用：先跑形态实验
                runVerifyExperiments()?.let { return@withContext it }

                AppLog.e(
                    "Verify", "verifyVerification",
                    "提交失败 rc=${primary.retcode} msg=${primary.message} body=${primary.raw}"
                )
                return@withContext ApiResult.Error(
                    "${primary.message} (code: ${primary.retcode})",
                    primary.retcode,
                    primary.raw,
                    "verifyVerification"
                )
            } catch (e: Exception) {
                // 真实异常的诊断信息：把服务端响应头一并暴露（HTTP code / content-type /
                // x-trace-id / server），便于"ClassCastException q6.d → q6.e"等场景
                // 确认服务端到底返回了什么（之前是 rawResponse="(无响应体)"盲猜）
                val ctx = "[http=$lastHttpCode ct=$lastContentType trace=$lastTraceId server=$lastServer]"
                val cause = e.cause?.let { " cause=${it.javaClass.simpleName}:${it.message}" }.orEmpty()
                AppLog.e(
                    "Verify", "verifyVerification",
                    "异常 ${e.javaClass.simpleName}: ${e.message}$cause ctx=$ctx", e
                )
                ApiResult.Error(
                    "提交安全验证异常: ${e.javaClass.simpleName} ${e.message}$cause\n诊断头: $ctx",
                    -1,
                    ctx,
                    "verifyVerification"
                )
            }
        }
    }

    /**
     * verify 提交的形态微调变体（2026-09-20 第三版，基线=缝合形态+challenge_game）。
     *
     * v2 定罪证据链（真机天然 A/B + 沙箱 W 系列）：
     * - 缝合形态（全量 Cookie+设备头+miHoYoBBS UA）真机 dailyNote=1034（设备层通过）
     * - 缝合形态+challenge_game 沙箱 verify=10306（风控层穿透）
     * - official-form 纯浏览器形态真机 dailyNote=5003（OkHttp TLS 冒充 Chrome 被识破）
     *
     * 基线若在真机仍被拒，以下变体用于兜底/定位：
     * - EXP-1 officialBrowser：纯官方浏览器形态（沙箱已证穿透，verify 层无设备校验）
     * - EXP-2 cookie=slim：缝合头 + 精简 H5 Cookie（定位 Cookie 因素）
     * - EXP-3 app_version=2.79.0：缝合头但版本换米游社 App 版本
     */
    private data class VerifyExperiment(
        val id: Int,
        val desc: String,
        val useOfficialBrowser: Boolean = false,
        val useSlimCookie: Boolean = false,
        val appVersion: String? = null
    )


    /** verifyVerification 单次提交的结果 */
    private data class VerifyOutcome(
        val retcode: Int,
        val message: String,
        val raw: String,
        val token: String?,
        // 响应头快照：用于 catch 块诊断（ClassCastException 时也能定位 HTTP 层）
        val lastHttpCode: Int = -1,
        val lastContentType: String = "",
        val lastTraceId: String = "",
        val lastServer: String = ""
    )

    /**
     * 向服务端提交一次 geetest 结果（不含重试逻辑）。
     *
     * 基线 = 缝合形态 + challenge_game（2026-09-20 v2 修复）：
     * 全量 Cookie（含 DEVICEFP 三件套）+ 设备 4 头 + miHoYoBBS UA +
     * X4 DS2 动态签名 + x-rpc-challenge_game:2。
     * 真机已证设备层可通过（旧版同头集 dailyNote=1034），沙箱 W1 已证
     * 加 challenge_game 后 verify 穿透风控层（10306）。
     *
     * @param exp 形态微调变体（null = 缝合基线）。详见 [VerifyExperiment]。
     */
    private fun submitVerification(
        body: String,
        slimCookie: String,
        fullCookie: String,
        uid: String,
        deviceId: String,
        deviceFp: String?,
        exp: VerifyExperiment? = null
    ): VerifyOutcome {
        val label = exp?.let { "[exp=${it.id} ${it.desc}] " } ?: ""
        val ds: String
        val builder = if (exp?.useOfficialBrowser == true) {
            // EXP-1：纯官方浏览器形态（沙箱已证穿透风控层）
            ds = OFFICIAL_DS
            Request.Builder()
                .url(API_VERIFY_VERIFICATION)
                .addOfficialBrowserHeaders(slimCookie, ds, OFFICIAL_APP_VERSION, uid)
        } else {
            val cookie = if (exp?.useSlimCookie == true) slimCookie else fullCookie
            ds = DsSigner.generateDS2(salt = DsSigner.Salt.X4, body = body)
            Request.Builder()
                .url(API_VERIFY_VERIFICATION)
                .addHybridHeaders(
                    cookie, ds, uid, deviceId, deviceFp.orEmpty(),
                    appVersion = exp?.appVersion
                )
        }

        val request = builder.post(body.toRequestBody("application/json".toMediaType())).build()

        // 完整请求头清单埋点：核对服务端实际看到的头（cookie/ds 由 AppLog 脱敏）
        AppLog.i(
            "Verify", "submitVerification",
            "${label}POST url=${API_VERIFY_VERIFICATION} body=$body ds=$ds"
        )
        AppLog.i(
            "Verify", "verifyHeaders",
            buildString {
                append(label)
                append("headers_count=").append(request.headers.size).append(' ')
                request.headers.names().sorted().forEach { name ->
                    append('[').append(name).append('=')
                        .append(request.header(name).orEmpty()).append("] ")
                }
            }
        )

        // 把响应头快照提取为局部函数，让所有 return 分支都能统一带上诊断信息
        // （避免之前 catch 时 rawResponse 永远为空、无法定位服务端到底返回了什么）
        fun snapshotOf(resp: okhttp3.Response): VerifyOutcomeHeader = VerifyOutcomeHeader(
            httpCode = resp.code,
            contentType = resp.header("content-type").orEmpty(),
            traceId = resp.header("x-trace-id").orEmpty(),
            server = resp.header("server").orEmpty()
        )

        val response = client.newCall(request).execute()
        val header = snapshotOf(response)
        val respBody = response.body?.string() ?: ""

        AppLog.i(
            "Verify", "submitVerification",
            "${label}resp http=${header.httpCode} ct=${header.contentType} " +
                "trace=${header.traceId} server=${header.server} " +
                "body_len=${respBody.length} body_prefix=${respBody.take(200)}"
        )

        if (!response.isSuccessful) {
            return VerifyOutcome(
                response.code, "HTTP ${response.code}", respBody, null,
                lastHttpCode = header.httpCode,
                lastContentType = header.contentType,
                lastTraceId = header.traceId,
                lastServer = header.server
            )
        }

        // 响应体可能为空/非 JSON（风控层直接断连或返回空体时）：
        // JsonParser.parseString("") 返回 JsonNull，直接 .asJsonObject 会抛
        // ClassCastException（混淆后表现为 "q6.d cannot be cast to q6.e"，
        // 即 JsonNull -> JsonObject）。这里显式判类型，把这种情况当作
        // "服务端返回空响应"上报，便于诊断。
        if (respBody.isBlank()) {
            return VerifyOutcome(
                -1, "服务端返回空响应（HTTP ${response.code}）", respBody, null,
                lastHttpCode = header.httpCode,
                lastContentType = header.contentType,
                lastTraceId = header.traceId,
                lastServer = header.server
            )
        }
        val parsed = try {
            JsonParser.parseString(respBody)
        } catch (e: Exception) {
            AppLog.e("Verify", "submitVerification", "响应 JSON 解析失败", e)
            return VerifyOutcome(
                -1, "响应解析失败: ${e.javaClass.simpleName}", respBody, null,
                lastHttpCode = header.httpCode,
                lastContentType = header.contentType,
                lastTraceId = header.traceId,
                lastServer = header.server
            )
        }
        if (!parsed.isJsonObject) {
            return VerifyOutcome(
                -1, "服务端返回非 JSON 对象", respBody, null,
                lastHttpCode = header.httpCode,
                lastContentType = header.contentType,
                lastTraceId = header.traceId,
                lastServer = header.server
            )
        }

        val json = parsed.asJsonObject
        val retcode = json.get("retcode")?.asIntSafe() ?: -1
        // message 字段可能是 JsonNull（服务端返回 {"data":null,"message":"","retcode":-1}），
        // 用 asStringSafe 防御，避免 JsonNull.asString 抛 UnsupportedOperationException
        val msg = json.get("message")?.asStringSafe().orEmpty()
        // data 字段同样可能是 null：用 getAsJsonObjectSafe 替换 getAsJsonObject，
        // 彻底避开 JsonNull → JsonObject 的 ClassCastException（混淆后即 q6.d → q6.e）
        val dataObj = json.getAsJsonObjectSafe("data")
        val token = dataObj?.get("challenge")?.asStringSafe()
        return VerifyOutcome(
            retcode, msg, respBody, token,
            lastHttpCode = header.httpCode,
            lastContentType = header.contentType,
            lastTraceId = header.traceId,
            lastServer = header.server
        )
    }

    /** 响应头快照：内部用，避免 VerifyOutcome 字段太多导致构造调用臃肿 */
    private data class VerifyOutcomeHeader(
        val httpCode: Int,
        val contentType: String,
        val traceId: String,
        val server: String
    )

    /** 提交成功后的收尾：缓存一次性 token、换新设备指纹 */
    private suspend fun cacheTokenAndRefresh(
        outcome: VerifyOutcome,
        uid: String
    ): ApiResult<String> {
        val token = outcome.token
        if (token.isNullOrBlank()) {
            return ApiResult.Error("响应缺少 challenge token", -1, outcome.raw, "verifyVerification")
        }
        // 缓存一次性 token：下一次业务请求（dailyNote）带上即放行
        if (uid.isNotBlank()) {
            challengeTokens[uid] = token
        }
        // 参考实现：验证通过后换新设备指纹，降低后续触发风控的概率
        deviceFpService.refreshDeviceFp()
        return ApiResult.Success(token)
    }

    /**
     * 两个验证接口共用的请求头集合（完整对齐米游社 H5 记录工具的请求特征）。
     *
     * 关键一致性要求：UA / app_version / tool_version / page / 机型 必须
     * 描述**同一个客户端身份**。混用 Android 机型 + H5 工具标识会被
     * 风控识别为伪造请求（retcode -1，message 为空）。
     *
     * 另两处易错点：
     * - x-rpc-device_id 必须**大写**（服务端按大写记录设备标识）
     * - Cookie 必须前置 DEVICEFP_SEED_ID/DEVICEFP_SEED_TIME/DEVICEFP 三字段，
     *   否则服务端认为设备指纹与 Cookie 不匹配
     */
    /**
     * 官方 H5 浏览器形态请求头（2026-09-20 终极修复）。
     *
     * 与官方原神战绩 H5 工具页的浏览器请求逐字段对齐（逆向 bundle 实证）：
     *   Cookie / DS(固定哈希) / UA(纯浏览器) / Referer / Origin /
     *   sec-fetch-* / x-rpc-client_type:5 / x-rpc-app_version:2.3.0 /
     *   x-rpc-challenge_game:2 / x-rpc-challenge_path / x-rpc-challenge_trace
     *
     * **不携带** device_id / device_fp / device_name / sys_version /
     * tool_version / page / X-Requested-With——官方浏览器形态没有这些头。
     * 真机实测该形态 dailyNote 会触发 5003（OkHttp TLS 冒充 Chrome UA 被
     * 设备验证层识破），仅作为 verify 实验兜底变体保留。
     */
    private fun Request.Builder.addOfficialBrowserHeaders(
        cookie: String,
        ds: String,
        appVersion: String,
        uid: String,
        traceOverride: String? = null
    ): Request.Builder {
        addHeader("User-Agent", USER_AGENT_BROWSER)
        addHeader("Accept", "application/json, text/plain, */*")
        addHeader("Referer", "https://webstatic.mihoyo.com/")
        addHeader("Origin", "https://webstatic.mihoyo.com")
        addHeader("sec-fetch-dest", "empty")
        addHeader("sec-fetch-site", "same-site")
        addHeader("Cookie", cookie)
        addHeader("x-rpc-client_type", "5")
        addHeader("x-rpc-app_version", appVersion)
        addHeader("DS", ds)
        addHeader("x-rpc-challenge_game", CHALLENGE_GAME_GENSHIN)
        addHeader("x-rpc-challenge_path", CHALLENGE_PATH)
        val trace = traceOverride ?: traceIds[uid]
        trace?.let { addHeader("x-rpc-challenge_trace", it) }
        return this
    }

    /**
     * 缝合形态请求头（2026-09-20 v2 基线）：旧版 21 头 + x-rpc-challenge_game。
     *
     * 头集与真机已证「设备验证层可通过」的旧版形态完全一致（dailyNote=1034
     * 而非 5003），唯一新增 x-rpc-challenge_game:2（verify 风控层真凶头，
     * 沙箱 W1 实证缝合+challenge_game → 10306 穿透）。
     *
     * 与 [addOfficialBrowserHeaders]（纯浏览器形态）的区别：保留 miHoYoBBS UA、
     * 设备 4 头（device_id/device_fp/device_name/sys_version）、tool_version、
     * page、X-Requested-With、language——官方 dora 框架在米游社 App WebView
     * 内也会带这套设备头（postMessage2App 双形态设计），App 场景下这是设备
     * 验证层认可的身份。
     */
    private fun Request.Builder.addHybridHeaders(
        cookie: String,
        ds: String,
        uid: String,
        deviceId: String,
        deviceFp: String,
        traceOverride: String? = null,
        appVersion: String? = null,
        withChallengeGame: Boolean = true
    ): Request.Builder {
        addHeader("User-Agent", USER_AGENT_VERIFY)
        addHeader("Accept", "application/json, text/plain, */*")
        addHeader("Content-Type", "application/json")
        addHeader("Referer", "https://webstatic.mihoyo.com/")
        addHeader("Origin", "https://webstatic.mihoyo.com")
        addHeader("X-Requested-With", "com.mihoyo.hyperion")
        addHeader("sec-fetch-dest", "empty")
        addHeader("sec-fetch-site", "same-site")
        addHeader("Cookie", cookie)
        addHeader("DS", ds)
        addHeader("x-rpc-app_version", appVersion ?: APP_VERSION_VERIFY)
        addHeader("x-rpc-challenge_path", CHALLENGE_PATH)
        val trace = traceOverride ?: traceIds[uid]
        trace?.let { addHeader("x-rpc-challenge_trace", it) }
        if (withChallengeGame) {
            addHeader("x-rpc-challenge_game", CHALLENGE_GAME_GENSHIN)
        }
        addHeader("x-rpc-client_type", "5")
        if (deviceFp.isNotBlank()) {
            addHeader("x-rpc-device_fp", deviceFp)
        }
        if (deviceId.isNotBlank()) {
            addHeader("x-rpc-device_id", deviceId)
        }
        addHeader("x-rpc-device_name", DEVICE_NAME_VERIFY)
        addHeader("x-rpc-language", "zh-cn")
        addHeader("x-rpc-page", RPC_PAGE)
        addHeader("x-rpc-sys_version", SYS_VERSION_VERIFY)
        addHeader("x-rpc-tool_version", TOOL_VERSION)
        return this
    }
}
