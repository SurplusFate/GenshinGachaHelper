package com.genshin.gachahelper.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore by preferencesDataStore(name = "auth_store")

/**
 * 授权凭证存储
 * 保存米游社登录凭证（stoken、ltuid 等）
 * authkey 是临时凭证，每次同步前通过 stoken 重新生成
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val STOKEN = stringPreferencesKey("stoken")
        val LTUID = stringPreferencesKey("ltuid")
        val MID = stringPreferencesKey("mid")
        val COOKIE_TOKEN = stringPreferencesKey("cookie_token")
        val LTOKEN = stringPreferencesKey("ltoken")
        val UID = stringPreferencesKey("uid")
        val SERVER = stringPreferencesKey("server")
        val NICKNAME = stringPreferencesKey("nickname")
        // 缓存上次生成的 authkey（有有效期）
        val AUTH_KEY = stringPreferencesKey("auth_key")
        val AUTH_KEY_TIME = stringPreferencesKey("auth_key_time")
        // AuthKey 绑定的 UID（防止 A UID 的 AuthKey 用到 B UID）
        val AUTH_KEY_UID = stringPreferencesKey("auth_key_uid")
        // 稳定设备 ID（UUID v3，基于 ANDROID_ID）
        val DEVICE_ID = stringPreferencesKey("device_id")
        // 设备指纹缓存（device_fp，用于风控）
        val DEVICE_FP = stringPreferencesKey("device_fp")
        val DEVICE_FP_SEED_ID = stringPreferencesKey("device_fp_seed_id")
        val DEVICE_FP_SEED_TIME = stringPreferencesKey("device_fp_seed_time")
    }

    val stokenFlow: Flow<String?> = context.authDataStore.data.map { it[Keys.STOKEN] }
    val uidFlow: Flow<String?> = context.authDataStore.data.map { it[Keys.UID] }

    suspend fun getStoken(): String? =
        context.authDataStore.data.first()[Keys.STOKEN]

    suspend fun getLtuid(): String? =
        context.authDataStore.data.first()[Keys.LTUID]

    suspend fun getMid(): String? =
        context.authDataStore.data.first()[Keys.MID]

    suspend fun getCookieToken(): String? =
        context.authDataStore.data.first()[Keys.COOKIE_TOKEN]

    suspend fun getLtoken(): String? =
        context.authDataStore.data.first()[Keys.LTOKEN]

    suspend fun getUid(): String? =
        context.authDataStore.data.first()[Keys.UID]

    suspend fun getServer(): String? =
        context.authDataStore.data.first()[Keys.SERVER]

    suspend fun getNickname(): String? =
        context.authDataStore.data.first()[Keys.NICKNAME]

    suspend fun getCachedAuthKey(): String? =
        context.authDataStore.data.first()[Keys.AUTH_KEY]

    suspend fun getCachedAuthKeyTime(): Long {
        val raw = context.authDataStore.data.first()[Keys.AUTH_KEY_TIME]
        return raw?.toLongOrNull() ?: 0L
    }

    /**
     * 获取稳定的设备 ID（UUID v3，基于 ANDROID_ID）
     * 首次调用时生成并持久化，后续直接读取
     * 参考 UIGF 文档：UUID.nameUUIDFromBytes(androidId.toByteArray())
     */
    suspend fun getOrCreateDeviceId(): String {
        val existing = context.authDataStore.data.first()[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing

        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"

        val deviceId = UUID.nameUUIDFromBytes(androidId.toByteArray()).toString()
        context.authDataStore.edit { it[Keys.DEVICE_ID] = deviceId }
        return deviceId
    }

    /**
     * 获取缓存的 device_fp
     */
    suspend fun getCachedDeviceFp(): String? =
        context.authDataStore.data.first()[Keys.DEVICE_FP]

    /**
     * 获取 device_fp 的种子 ID 与时间戳。
     * 米游社要求把这两个值与 device_fp 一起以 Cookie 形式回传
     * （DEVICEFP_SEED_ID / DEVICEFP_SEED_TIME），缺失会被风控拒绝。
     */
    suspend fun getDeviceFpSeed(): Pair<String, String> {
        val prefs = context.authDataStore.data.first()
        return (prefs[Keys.DEVICE_FP_SEED_ID] ?: "") to
            (prefs[Keys.DEVICE_FP_SEED_TIME] ?: "")
    }

    /**
     * 构造带设备指纹字段的 Cookie 字符串。
     *
     * 米游社通过 Cookie 中的这三个字段校验"设备指纹与请求来源是否匹配"：
     *   DEVICEFP_SEED_ID={seed};DEVICEFP_SEED_TIME={time};DEVICEFP={fp};
     * 仅带 x-rpc-device_fp 请求头是不够的——服务端会认为指纹与 Cookie
     * 不一致，验证接口直接返回失败（表现为空 message 的 -1）。
     *
     * @param baseCookie 原始登录 Cookie
     */
    suspend fun buildCookieWithDeviceFp(baseCookie: String): String {
        if (baseCookie.isBlank()) return baseCookie
        val fp = getCachedDeviceFp().orEmpty()
        val (seedId, seedTime) = getDeviceFpSeed()
        if (fp.isBlank()) return baseCookie
        // seed 缺失时不要拼空字段（DEVICEFP_SEED_ID=; 会被服务端判为非法）
        val fpPrefix = if (seedId.isBlank() || seedTime.isBlank()) {
            "DEVICEFP=$fp; "
        } else {
            "DEVICEFP_SEED_ID=$seedId; DEVICEFP_SEED_TIME=$seedTime; DEVICEFP=$fp; "
        }
        // 分隔符统一用 "; "（分号+空格）：与 buildCookieString() 保持一致。
        // 原实现此处用 ";" 无空格、而 baseCookie 内部用 "; " 分隔，
        // 混用两种风格会让服务端 Cookie 解析器在边界处取到带前导空格的
        // 键名（如 " ltuid"），进而判定登录态无效（retcode 10001）。
        return fpPrefix + baseCookie
    }

    /** 保存 device_fp 及其种子信息 */
    suspend fun saveDeviceFp(fp: String, seedId: String, seedTime: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.DEVICE_FP] = fp
            prefs[Keys.DEVICE_FP_SEED_ID] = seedId
            prefs[Keys.DEVICE_FP_SEED_TIME] = seedTime
        }
    }

    /**
     * 清除缓存的 device_fp（含种子信息）
     * 供 5003（设备验证未通过）时强制重新向 getFp 申请指纹，避免脏缓存永久生效
     */
    suspend fun clearDeviceFp() {
        context.authDataStore.edit { prefs ->
            prefs.remove(Keys.DEVICE_FP)
            prefs.remove(Keys.DEVICE_FP_SEED_ID)
            prefs.remove(Keys.DEVICE_FP_SEED_TIME)
        }
    }

    /**
     * 保存登录 Cookie 中提取的凭证（stoken、ltuid 等）
     */
    /**
     * 保存登录凭据。
     *
     * 注意：新版通行证扫码登录只下发 ltoken_v2 / cookie_token_v2，
     * **不一定有 stoken**（stoken 恒为 null）。因此这里 stoken 允许为空，
     * 只要 ltoken / cookie_token 之一存在即视为有效登录态
     * （便笺、角色列表等接口用 cookie_token/ltoken 即可鉴权）。
     */
    suspend fun saveLoginCredentials(
        stoken: String?,
        ltuid: String,
        mid: String? = null,
        cookieToken: String? = null,
        ltoken: String? = null
    ) {
        context.authDataStore.edit { prefs ->
            if (!stoken.isNullOrBlank()) prefs[Keys.STOKEN] = stoken
            prefs[Keys.LTUID] = ltuid
            mid?.let { prefs[Keys.MID] = it }
            cookieToken?.let { prefs[Keys.COOKIE_TOKEN] = it }
            ltoken?.let { prefs[Keys.LTOKEN] = it }
        }
    }

    /**
     * 保存游戏角色信息（UID、服务器、昵称）
     */
    suspend fun saveGameRole(
        uid: String,
        server: String,
        nickname: String? = null
    ) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.UID] = uid
            prefs[Keys.SERVER] = server
            nickname?.let { prefs[Keys.NICKNAME] = it }
        }
    }

    /**
     * 缓存生成的 authkey（24 小时有效）
     * 同时绑定 UID，防止 A UID 的 AuthKey 被用到 B UID
     */
    suspend fun cacheAuthKey(authkey: String) {
        val uid = getUid() ?: ""
        context.authDataStore.edit { prefs ->
            prefs[Keys.AUTH_KEY] = authkey
            prefs[Keys.AUTH_KEY_TIME] = System.currentTimeMillis().toString()
            prefs[Keys.AUTH_KEY_UID] = uid
        }
    }

    suspend fun getCachedAuthKeyIfFresh(): String? {
        val prefs = context.authDataStore.data.first()
        val key = prefs[Keys.AUTH_KEY] ?: return null
        val time = prefs[Keys.AUTH_KEY_TIME]?.toLongOrNull() ?: 0L
        val cachedUid = prefs[Keys.AUTH_KEY_UID] ?: ""
        val currentUid = prefs[Keys.UID] ?: ""

        // AuthKey 必须属于当前 UID
        if (cachedUid.isNotBlank() && currentUid.isNotBlank() && cachedUid != currentUid) {
            return null
        }

        val ageMs = System.currentTimeMillis() - time
        // authkey 有效期约 24 小时，提前到 20 小时就刷新
        return if (ageMs < 20 * 60 * 60 * 1000) key else null
    }

    /**
     * 清除缓存的 AuthKey（用于 -100 失效场景）
     */
    suspend fun clearAuthKey() {
        context.authDataStore.edit { prefs ->
            prefs.remove(Keys.AUTH_KEY)
            prefs.remove(Keys.AUTH_KEY_TIME)
            prefs.remove(Keys.AUTH_KEY_UID)
        }
    }

    /**
     * 校验缓存的 AuthKey 是否属于当前 UID，不匹配则清除
     */
    suspend fun validateAuthKeyForUid(currentUid: String) {
        val prefs = context.authDataStore.data.first()
        val cachedUid = prefs[Keys.AUTH_KEY_UID] ?: return
        if (cachedUid.isNotBlank() && cachedUid != currentUid) {
            clearAuthKey()
        }
    }

    /**
     * 构建 API 请求用的 Cookie 字符串
     * 包含 v2 版本的 token，兼容新旧接口
     *
     * 一次性读取 DataStore 取出所有字段，避免每个 token 单独 first() 造成的
     * 多次串行 IO 与反序列化（原先 5 次 first() → 现在 1 次）。
     *
     * 注意：stoken 不是必须的，只要有 cookie_token + ltuid 或 ltoken + ltuid
     * 就可以调用大部分接口。stoken 只是用于换取 cookie_token/ltoken 的中间凭证。
     */
    suspend fun buildCookieString(): String {
        val prefs = context.authDataStore.data.first()
        val stoken = prefs[Keys.STOKEN] ?: ""
        val ltuid = prefs[Keys.LTUID] ?: ""
        val mid = prefs[Keys.MID] ?: ""
        val cookieToken = prefs[Keys.COOKIE_TOKEN] ?: ""
        val ltoken = prefs[Keys.LTOKEN] ?: ""

        // 至少需要 ltuid + (cookie_token 或 ltoken 或 stoken) 才算有效
        if (ltuid.isBlank() || (cookieToken.isBlank() && ltoken.isBlank() && stoken.isBlank())) {
            return ""
        }

        return buildString {
            if (stoken.isNotBlank()) {
                append("stoken=$stoken")
                append("; stoken_v2=$stoken")
            }
            if (ltuid.isNotBlank()) {
                if (stoken.isNotBlank()) append("; ")
                append("stuid=$ltuid; ltuid=$ltuid")
                append("; ltuid_v2=$ltuid; account_id=$ltuid; account_id_v2=$ltuid")
            }
            if (mid.isNotBlank()) {
                append("; mid=$mid; mid_v2=$mid; account_mid_v2=$mid; ltmid_v2=$mid")
            }
            if (cookieToken.isNotBlank()) {
                append("; cookie_token=$cookieToken; cookie_token_v2=$cookieToken")
            }
            if (ltoken.isNotBlank()) {
                append("; ltoken=$ltoken; ltoken_v2=$ltoken")
            }
        }
    }

    /**
     * 浏览器 H5 登录态形态的精简 Cookie（2026-09-20 终极修复）。
     *
     * 逆向米哈游官方原神战绩 H5 工具页实证：浏览器访问时只携带
     * stuid / ltuid / account_id / cookie_token 四个键——不带 stoken*
     * 全家桶、mid* 系列、*v2 别名与 DEVICEFP* 指纹字段（那些是
     * App 扫码凭据/设备指纹特征，浏览器登录态不会有）。
     *
     * 官方形态实验（沙箱 lab2）：该 Cookie + challenge_game:2 头的组合
     * 可穿透 verify 风控层（-1 空 message → 10306 业务层错误）。
     */
    fun buildSlimBrowserCookie(baseCookie: String): String {
        if (baseCookie.isBlank()) return baseCookie
        val keep = setOf("stuid", "ltuid", "account_id", "cookie_token")
        return baseCookie.split("; ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { kv -> kv.substringBefore('=').trim() in keep }
            .joinToString("; ")
    }

    suspend fun isLoggedIn(): Boolean {        val prefs = context.authDataStore.data.first()
        val ltuid = prefs[Keys.LTUID] ?: return false
        val stoken = prefs[Keys.STOKEN]
        val cookieToken = prefs[Keys.COOKIE_TOKEN]
        val ltoken = prefs[Keys.LTOKEN]
        return ltuid.isNotBlank() &&
            (!stoken.isNullOrBlank() || !cookieToken.isNullOrBlank() || !ltoken.isNullOrBlank())
    }

    suspend fun logout() {
        context.authDataStore.edit { it.clear() }
    }
}
