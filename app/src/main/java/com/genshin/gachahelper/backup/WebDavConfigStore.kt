package com.genshin.gachahelper.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 备份配置存储（2026-09-21 新增）
 *
 * 独立 DataStore（webdav_store），与 auth_store / signin_store / settings_store 同范式。
 * 存放：开关、服务器地址、账号、密码、远端目录，以及最近一次备份的结果快照。
 */
private val Context.webDavDataStore by preferencesDataStore(name = "webdav_store")

/**
 * WebDAV 配置快照
 *
 * @param enabled       同步成功后是否自动备份
 * @param url           服务器地址（如 https://dav.jianguoyun.com/dav/ 或 http://192.168.1.10:5244/dav）
 * @param username      账号
 * @param password      密码 / 应用授权码（Nextcloud、坚果云必须用「应用密码」）
 * @param remoteDir     远端目录（默认 /GenshinGachaHelper，不存在会自动创建）
 * @param lastBackupTime 上次备份时间戳（0 = 从未备份）
 * @param lastBackupStatus 上次备份结果文本（成功/失败原因）
 * @param lastBackupFile 上次备份的远端文件名
 */
data class WebDavConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val remoteDir: String = "/GenshinGachaHelper",
    val lastBackupTime: Long = 0L,
    val lastBackupStatus: String = "",
    val lastBackupFile: String = ""
) {
    /** 是否已填够可用的配置（地址为必填项） */
    val isConfigured: Boolean get() = url.isNotBlank()
}

@Singleton
class WebDavConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val URL = stringPreferencesKey("url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val REMOTE_DIR = stringPreferencesKey("remote_dir")
        val LAST_TIME = longPreferencesKey("last_backup_time")
        val LAST_STATUS = stringPreferencesKey("last_backup_status")
        val LAST_FILE = stringPreferencesKey("last_backup_file")
    }

    val configFlow: Flow<WebDavConfig> = context.webDavDataStore.data.map { p ->
        WebDavConfig(
            enabled = p[Keys.ENABLED] ?: false,
            url = p[Keys.URL] ?: "",
            username = p[Keys.USERNAME] ?: "",
            password = p[Keys.PASSWORD] ?: "",
            remoteDir = p[Keys.REMOTE_DIR] ?: "/GenshinGachaHelper",
            lastBackupTime = p[Keys.LAST_TIME] ?: 0L,
            lastBackupStatus = p[Keys.LAST_STATUS] ?: "",
            lastBackupFile = p[Keys.LAST_FILE] ?: ""
        )
    }

    suspend fun current(): WebDavConfig = configFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.webDavDataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setUrl(url: String) {
        context.webDavDataStore.edit { it[Keys.URL] = url.trim() }
    }

    suspend fun setUsername(username: String) {
        context.webDavDataStore.edit { it[Keys.USERNAME] = username.trim() }
    }

    suspend fun setPassword(password: String) {
        context.webDavDataStore.edit { it[Keys.PASSWORD] = password }
    }

    suspend fun setRemoteDir(dir: String) {
        context.webDavDataStore.edit { it[Keys.REMOTE_DIR] = dir.trim() }
    }

    /**
     * 记录一次备份结果。
     * @param success 是否成功；失败时 lastBackupTime 仍更新（便于 UI 展示"上次尝试"），
     *                由 status 文本区分成功/失败。
     */
    suspend fun recordBackupResult(status: String, file: String) {
        context.webDavDataStore.edit {
            it[Keys.LAST_TIME] = System.currentTimeMillis()
            it[Keys.LAST_STATUS] = status
            it[Keys.LAST_FILE] = file
        }
    }
}
