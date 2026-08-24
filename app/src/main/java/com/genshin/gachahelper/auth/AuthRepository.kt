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
     * 保存 device_fp 及其种子信息
     */
    suspend fun saveDeviceFp(fp: String, seedId: String, seedTime: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.DEVICE_FP] = fp
            prefs[Keys.DEVICE_FP_SEED_ID] = seedId
            prefs[Keys.DEVICE_FP_SEED_TIME] = seedTime
        }
    }

    /**
     * 保存登录 Cookie 中提取的凭证（stoken、ltuid 等）
     */
    suspend fun saveLoginCredentials(
        stoken: String,
        ltuid: String,
        mid: String? = null,
        cookieToken: String? = null,
        ltoken: String? = null
    ) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.STOKEN] = stoken
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
     */
    suspend fun cacheAuthKey(authkey: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.AUTH_KEY] = authkey
            prefs[Keys.AUTH_KEY_TIME] = System.currentTimeMillis().toString()
        }
    }

    suspend fun getCachedAuthKeyIfFresh(): String? {
        val key = getCachedAuthKey() ?: return null
        val time = getCachedAuthKeyTime()
        val ageMs = System.currentTimeMillis() - time
        // authkey 有效期约 24 小时，提前到 20 小时就刷新
        return if (ageMs < 20 * 60 * 60 * 1000) key else null
    }

    /**
     * 构建 API 请求用的 Cookie 字符串
     * 包含 v2 版本的 token，兼容新旧接口
     */
    suspend fun buildCookieString(): String {
        val stoken = getStoken() ?: return ""
        val ltuid = getLtuid() ?: ""
        val mid = getMid() ?: ""
        val cookieToken = getCookieToken() ?: ""
        val ltoken = getLtoken() ?: ""
        return buildString {
            append("stoken=$stoken")
            append("; stoken_v2=$stoken")
            if (ltuid.isNotBlank()) {
                append("; stuid=$ltuid; ltuid=$ltuid")
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

    suspend fun isLoggedIn(): Boolean {
        return !getStoken().isNullOrBlank()
    }

    suspend fun logout() {
        context.authDataStore.edit { it.clear() }
    }
}
