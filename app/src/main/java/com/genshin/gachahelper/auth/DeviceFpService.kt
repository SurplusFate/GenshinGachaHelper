package com.genshin.gachahelper.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备指纹服务
 *
 * 通过 https://public-data-api.mihoyo.com/device-fp/api/getFp 获取 device_fp
 * device_fp 用于风控验证，非法/自造的指纹会导致便笺接口返回 5003（缺少合法设备信息）
 *
 * 参考: UIGF-org/mihoyo-api-collect（device-fp/api/getFp 章节）
 *
 * ── 2026-09 修复说明（5003 问题根因）──────────────────────────────
 * 旧版 buildLocalFingerprint() 生成 12 位 [a-z0-9] 随机串，它同时被用作
 * getFp 请求体的 device_fp 参数。实测该参数必须严格为 13 位十六进制
 * [0-9a-f]{13}：12/14 位或含 g-z 字符时服务端一律返回
 * {"data":{"code":403,"msg":"传入的参数有误"}}，永远拿不到合法指纹。
 * 于是旧版走了本地兜底，把自造的 12 位串持久化缓存并带上便笺请求，
 * 米哈游风控判定设备不合法 → retcode 5003，且缓存永不失效 → 死循环。
 * 修复：占位指纹改为 13 位 hex；缓存指纹先做格式校验，非法/历史脏数据
 * 直接废弃重新申请；另提供 refreshDeviceFp() 供 5003 时强制重取。
 * ──────────────────────────────────────────────────────────────
 */
@Singleton
class DeviceFpService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val client: OkHttpClient
) {
    companion object {
        private const val API_GET_FP =
            "https://public-data-api.mihoyo.com/device-fp/api/getFp"

        /**
         * 占位指纹长度：13 位十六进制。
         * 实测约束（2026-09，平台 platform=2）：
         *   [0-9a-f]{13} -> data.code=200（成功，返回服务器签发的指纹）
         *   12 位 hex / 14 位 hex / 13 位含 g-z 字母 / 12 位 [a-z0-9] -> data.code=403
         */
        private const val FINGERPRINT_LENGTH = 13
        private const val FINGERPRINT_CHARS = "0123456789abcdef"

        /** 服务器签发的合法指纹格式（实测样本：38d81c8187991 / 38d81c8192ea1） */
        private val VALID_FP_PATTERN = Regex("^[0-9a-f]{13}$")

        /** 设备画像使用的米游社版本号（与 iOS 客户端一致） */
        private const val APP_VERSION_FP = "2.75.1"
        private const val USER_AGENT_FP =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) miHoYoBBS/$APP_VERSION_FP"
    }

    /** 最近一次 device_fp 来源：cache / api / local（真机诊断用） */
    @Volatile
    var lastFpSource: String = "none"
        private set

    /** 最近一次 getFp 接口状态，形如 http=200,retcode=0,code=200（真机诊断用） */
    @Volatile
    var lastGetFpStatus: String = "n/a"
        private set

    /** 指纹是否为服务器签发的合法格式（13 位 hex）；自造/历史脏数据一律判非法 */
    private fun isValidFingerprint(fp: String?): Boolean =
        !fp.isNullOrBlank() && VALID_FP_PATTERN.matches(fp)

    /**
     * 获取并缓存 device_fp
     * 优先读缓存（仅当缓存格式合法），否则请求 API，最后才本地兜底。
     */
    suspend fun getOrCreateDeviceFp(): String? = withContext(Dispatchers.IO) {
        // 先尝试读缓存：旧版本写入的 12 位 [a-z0-9] 自造指纹不合法，直接废弃
        val cached = authRepository.getCachedDeviceFp()
        if (isValidFingerprint(cached)) {
            lastFpSource = "cache"
            return@withContext cached
        }

        // 1) 优先走官方接口生成
        val fromApi = requestDeviceFpFromApi()
        if (isValidFingerprint(fromApi)) {
            lastFpSource = "api"
            return@withContext fromApi
        }

        // 2) 兜底：本地生成 13 位 hex 占位指纹并持久化
        //    注意：这只是"格式正确"的占位，未经服务器签发，风控仍可能拒绝
        //    （便笺接口表现为 5003）。getFp 恢复后应通过 refreshDeviceFp() 换新。
        val local = buildLocalFingerprint()
        authRepository.saveDeviceFp(local, "local", System.currentTimeMillis().toString())
        lastFpSource = "local"
        local
    }

    /**
     * 强制刷新 device_fp：丢弃缓存后重新向服务器申请。
     * 供便笺接口遇到 5003（设备验证未通过）时调用，避免自造指纹被永久缓存。
     */
    suspend fun refreshDeviceFp(): String? = withContext(Dispatchers.IO) {
        authRepository.clearDeviceFp()
        val fromApi = requestDeviceFpFromApi()
        if (isValidFingerprint(fromApi)) {
            lastFpSource = "api(refresh)"
            fromApi
        } else {
            // 申请失败仍回退占位，但记录状态便于诊断
            val local = buildLocalFingerprint()
            authRepository.saveDeviceFp(local, "local", System.currentTimeMillis().toString())
            lastFpSource = "local(refresh)"
            local
        }
    }

    /**
     * 调用 device-fp/api/getFp 生成指纹
     *
     * 注意：该接口即使参数不全也返回外层 retcode=0，真实结果在 data.code，
     * 必须同时校验 retcode==0 && data.code==200 && device_fp 非空，否则拿到的是空指纹。
     * 实测：请求体 device_fp 参数必须是 13 位 hex，否则 data.code=403"传入的参数有误"。
     */
    private suspend fun requestDeviceFpFromApi(): String? = withContext(Dispatchers.IO) {
        val deviceId = authRepository.getOrCreateDeviceId()
        val extFields = buildExtFields(deviceId)
        val seedId = UUID.randomUUID().toString()
        val seedTime = System.currentTimeMillis().toString()

        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("seed_id", seedId)
            put("seed_time", seedTime)
            put("platform", "2")
            // 占位指纹：13 位 hex（服务端强校验，格式错误直接 403）
            put("device_fp", buildLocalFingerprint())
            put("app_name", "bbs_cn")
            put("ext_fields", extFields)
        }.toString()

        AppLog.i(
            "Fp", "requestDeviceFpFromApi",
            "POST url=$API_GET_FP body_len=${body.length} " +
                "deviceId=${deviceId.uppercase()} seedId=$seedId"
        )

        val request = Request.Builder()
            .url(API_GET_FP)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", USER_AGENT_FP)
            .addHeader("x-rpc-app_version", APP_VERSION_FP)
            .addHeader("x-rpc-client_type", "5")
            // device_id 与服务端约定一致，统一大写
            .addHeader("x-rpc-device_id", deviceId.uppercase())
            .addHeader("x-rpc-sys_version", "18.0")
            .addHeader("x-rpc-device_name", "iPhone")
            .addHeader("x-rpc-language", "zh-cn")
            .addHeader("Referer", "https://webstatic.mihoyo.com/")
            .addHeader("Origin", "https://webstatic.mihoyo.com")
            .addHeader("X-Requested-With", "com.mihoyo.hyperion")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                val json = JSONObject(raw)
                val retcode = json.optInt("retcode", -1)
                val data = json.optJSONObject("data")
                val code = data?.optInt("code", -1) ?: -1
                val deviceFp = data?.optString("device_fp").orEmpty()
                lastGetFpStatus = "http=${response.code},retcode=$retcode,code=$code"
                AppLog.i(
                    "Fp", "requestDeviceFpFromApi",
                    "resp http=${response.code} retcode=$retcode data_code=$code " +
                        "device_fp=${if (deviceFp.isBlank()) "<empty>" else deviceFp} " +
                        "msg=${data?.optString("msg").orEmpty()}"
                )
                if (retcode == 0 && code == 200 && deviceFp.isNotBlank()) {
                    authRepository.saveDeviceFp(deviceFp, seedId, seedTime)
                    return@withContext deviceFp
                }
            }
        } catch (e: Exception) {
            lastGetFpStatus = "ex:${e.javaClass.simpleName}"
            AppLog.e("Fp", "requestDeviceFpFromApi", "异常 ${e.javaClass.simpleName}: ${e.message}", e)
        }
        null
    }

    /** 生成本地占位指纹：13 位十六进制（与服务器签发格式一致） */
    private fun buildLocalFingerprint(): String {
        return (1..FINGERPRINT_LENGTH).map { FINGERPRINT_CHARS.random() }.joinToString("")
    }

    /**
     * 构建设备信息 ext_fields（JSON 字符串）
     *
     * ── 2026-09 修复（getFp 返回 500 的根因）──────────────────────
     * 旧版用 Android 真实硬件信息填充（model=真机型号、productName=Build.PRODUCT、
     * osVersion=Android 版本、sdkVersion=API 等级、screenSize=真实分辨率）。
     * 实测：这样构造的请求体在 getFp 接口恒定返回
     *   {"data":{"code":500,"msg":"内部错误"}}
     * 服务端不仅不签发指纹，还把请求里的占位 device_fp 原样回显，
     * 导致客户端把"从未被签发的假指纹"缓存下来带去 dailyNote，
     * 风控识别为伪造设备 → retcode 1034。
     *
     * 对照实测（ext_fields 内容为唯一变量）：
     *   iOS 风格完整字段  -> code=200，签发 fp（形如 38d81c82xxxxx）
     *   Android 真机字段  -> code=500 内部错误
     *
     * 修复：改用米游社 iOS 客户端的设备画像（与官方 miHoYoBBS iOS 端一致），
     * 字段顺序与官方实现保持一致。app_name 仍为 bbs_cn。
     * ──────────────────────────────────────────────────────────────
     */
    private fun buildExtFields(deviceId: String): String {
        // 稳定的设备标识：同一台手机每次生成保持一致，避免指纹漂移
        val stableUuid = rememberStableUuid(deviceId)

        val extFields = JSONObject().apply {
            put("cpuType", "CPU_TYPE_ARM64")
            put("romCapacity", "242989")
            put("productName", "iPhone15,2")
            put("romRemain", "20617")
            put("manufacturer", "Apple")
            put("appMemory", "120")
            put("hostname", "iPhone")
            put("screenSize", "393x852")
            put("osVersion", "18.0")
            put("aaid", stableUuid)
            put("vendor", "--")
            put("accelerometer", "0.033539x-0.684265x-0.757690")
            put("buildTags", "release-keys")
            put("model", "iPhone15,2")
            put("brand", "Apple")
            put("oaid", stableUuid)
            put("hardware", "iPhone")
            put("deviceType", "iPhone")
            put("devId", "REL")
            put("buildTime", "1725149453887")
            put("buildUser", "root")
            put("ramCapacity", "5665")
            put("magnetometer", "640.353210x-105.483749x-192.943878")
            put("display", "iPhone")
            put("ramRemain", "104")
            put("deviceInfo", "Apple/iPhone15,2")
            put("gyroscope", "-0.097501x0.010854x0.020438")
            put("vaid", stableUuid)
            put("buildType", "user")
            put("sdkVersion", "18")
            put("board", "iPhone")
            put("serialNumber", "unknown")
            put("isSimInserted", "1")
            put("proxyStatus", "1")
            put("isPushEnabled", "0")
            put("ramCapacityDetail", "5665")
            put("batteryStatus", "100")
            put("chargeStatus", "3")
            put("screenBrightness", "0.600")
            put("networkType", "5G")
            put("hasVpn", "1")
            put("isJailBreak", "0")
            put("packageName", "com.miHoYo.mhybbs")
            put("packageVersion", APP_VERSION_FP)
            put("deviceName", "iPhone")
            put("appInstallTimeDiff", "${System.currentTimeMillis() - 100_000}")
            put("appUpdateTimeDiff", "1725558423064")
            put("userAgent", USER_AGENT_FP)
        }
        return extFields.toString()
    }

    /**
     * 生成/复用稳定的设备 UUID。
     *
     * 旧版每次请求都生成新的 aaid/oaid/vaid，导致同台设备每次上报的
     * 设备画像都不同，是风控判定"批量机器请求"的特征之一。
     * 这里以 deviceId 为种子做确定性映射，保证同一设备始终一致。
     */
    private fun rememberStableUuid(deviceId: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val bytes = md.digest(deviceId.toByteArray())
            val hex = bytes.joinToString("") { "%02x".format(it) }
            // 转成标准 UUID 形态（8-4-4-4-12）
            buildString {
                append(hex.substring(0, 8)).append('-')
                append(hex.substring(8, 12)).append('-')
                append(hex.substring(12, 16)).append('-')
                append(hex.substring(16, 20)).append('-')
                append(hex.substring(20, 32))
            }
        } catch (_: Exception) {
            deviceId
        }
    }
}
