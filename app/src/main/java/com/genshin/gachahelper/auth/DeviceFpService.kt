package com.genshin.gachahelper.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备指纹服务
 *
 * 通过 https://public-data-api.mihoyo.com/device-fp/api/getFp 获取 device_fp
 * device_fp 用于风控验证，缺少时米游社会返回 -3503 "当前设备或网络环境存在风险"
 *
 * 参考: UIGF-org/mihoyo-api-collect PR #25
 */
@Singleton
class DeviceFpService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository
) {
    companion object {
        private const val API_GET_FP =
            "https://public-data-api.mihoyo.com/device-fp/api/getFp"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 生成并缓存 device_fp
     * 首次调用时请求 API，后续从 AuthRepository 读取缓存
     */
    suspend fun getOrCreateDeviceFp(): String? = withContext(Dispatchers.IO) {
        // 先尝试读缓存
        val cached = authRepository.getCachedDeviceFp()
        if (!cached.isNullOrBlank()) return@withContext cached

        // 请求 API 生成
        val deviceId = authRepository.getOrCreateDeviceId()
        val extFields = buildExtFields(deviceId)
        val seedId = UUID.randomUUID().toString()
        val seedTime = System.currentTimeMillis().toString()

        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("seed_id", seedId)
            put("seed_time", seedTime)
            put("platform", "2")
            put("device_fp", "")
            put("app_name", "bbs_cn")
            put("ext_fields", extFields)
        }.toString()

        val request = Request.Builder()
            .url(API_GET_FP)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", DsSigner.USER_AGENT)
            .addHeader("x-rpc-app_version", DsSigner.Salt.APP_VERSION)
            .addHeader("x-rpc-client_type", "5")
            .addHeader("Referer", "https://webstatic.mihoyo.com/")
            .addHeader("Origin", "https://webstatic.mihoyo.com")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                val json = JSONObject(raw)
                val retcode = json.optInt("retcode", -1)
                if (retcode == 0) {
                    val deviceFp = json.optJSONObject("data")?.optString("device_fp")
                    if (!deviceFp.isNullOrBlank()) {
                        authRepository.saveDeviceFp(deviceFp, seedId, seedTime)
                        return@withContext deviceFp
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略错误，返回 null
        }
        null
    }

    /**
     * 构建设备信息 ext_fields（JSON 字符串）
     * 使用 Android 真实硬件信息
     */
    private fun buildExtFields(deviceId: String): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: ""

        val metrics = context.resources.displayMetrics
        val screenSize = "${metrics.widthPixels}x${metrics.heightPixels}"

        val extFields = JSONObject().apply {
            put("cpuType", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
            put("romCapacity", "512")
            put("productName", Build.PRODUCT)
            put("romRemain", "422")
            put("manufacturer", Build.MANUFACTURER)
            put("appMemory", "512")
            put("hostname", "android-build")
            put("screenSize", screenSize)
            put("osVersion", Build.VERSION.RELEASE)
            put("aaid", deviceId)
            put("vendor", "")
            put("accelerometer", "0.44027936x7.256833x6.422336")
            put("buildTags", Build.TAGS)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("oaid", deviceId)
            put("hardware", Build.HARDWARE)
            put("deviceType", Build.DEVICE)
            put("devId", "REL")
            put("buildTime", "${Build.TIME}")
            put("buildUser", "root")
            put("ramCapacity", "469679")
            put("magnetometer", "20.081251x-27.487501x2.1937501")
            put("display", Build.DISPLAY)
            put("ramRemain", "215344")
            put("deviceInfo", "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}:${Build.VERSION.RELEASE}/${Build.ID}/${Build.VERSION.INCREMENTAL}:user/release-keys")
            put("gyroscope", "0.030226856x0.014647375x0.010652636")
            put("vaid", deviceId)
            put("buildType", "user")
            put("sdkVersion", "${Build.VERSION.SDK_INT}")
            put("board", Build.BOARD)
            put("userAgent", DsSigner.USER_AGENT)
        }
        return extFields.toString()
    }
}
