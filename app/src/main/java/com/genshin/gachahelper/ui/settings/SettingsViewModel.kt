package com.genshin.gachahelper.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.config.importer.ConfigImporter
import com.genshin.gachahelper.config.store.ConfigStore
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.sync.GachaDataImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isLoggedIn: Boolean = false,
    val uid: String? = null,
    val nickname: String? = null,
    val configVersion: String = "加载中...",
    val configUrl: String = "",
    val errorLogCount: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val configStore: ConfigStore,
    private val configImporter: ConfigImporter,
    private val gachaRepository: GachaRepository,
    private val gachaDataImporter: GachaDataImporter,
    private val sessionEventBus: SessionEventBus,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    init {
        // 监听全局会话事件：登录/退出/导入/清除后需重新 loadSettings 以刷新登录态与配置显示
        // 否则用户先访问过 Settings（ViewModel 已 saveState 存活），后续登录/退出后切回
        // Settings 会看到旧的"未登录/旧 UID"状态，必须重启 App 才刷新。
        viewModelScope.launch {
            sessionEventBus.events.collect { event ->
                when (event) {
                    SessionEvent.LoginCompleted,
                    SessionEvent.LogoutCompleted,
                    SessionEvent.DataCleared,
                    SessionEvent.DataImported -> loadSettings()
                    else -> Unit
                }
            }
        }
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            val loggedIn = authRepository.isLoggedIn()
            val uid = authRepository.getUid()
            val nickname = authRepository.getNickname()
            val config = configStore.getCurrentConfig()

            _uiState.value = SettingsUiState(
                isLoggedIn = loggedIn,
                uid = uid,
                nickname = nickname,
                configVersion = config.version,
                configUrl = config.api.url.take(30) + "..."
            )
        }
    }

    fun importConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                val config = configImporter.importFromUri(uri)
                _importMessage.value = "导入成功！版本: ${config.version}"
                loadSettings()
            } catch (e: Exception) {
                _importMessage.value = "导入失败: ${e.message}"
            }
        }
    }

    fun resetConfig() {
        viewModelScope.launch {
            try {
                configStore.resetToDefault()
                _importMessage.value = "已恢复默认配置"
                loadSettings()
            } catch (e: Exception) {
                _importMessage.value = "恢复失败: ${e.message}"
            }
        }
    }

    fun clearImportMessage() {
        _importMessage.value = null
    }

    // ------------------------------------------------------------------
    // 导入历史抽卡数据（UIGF 格式）
    // ------------------------------------------------------------------

    fun importGachaData(uri: Uri) {
        viewModelScope.launch {
            _importMessage.value = "正在导入..."
            try {
                val result = gachaDataImporter.importFromUri(uri)
                _importMessage.value = if (result.success) {
                    // 通知全局：数据已导入，其他页面刷新
                    sessionEventBus.emit(SessionEvent.DataImported)
                    "导入完成: ${result.totalImported} 条新增, ${result.skipped} 条跳过 (UID: ${result.uid})"
                } else {
                    "导入失败: ${result.message}"
                }
            } catch (e: Exception) {
                _importMessage.value = "导入异常: ${e.message}"
            }
        }
    }

    // ------------------------------------------------------------------
    // 导出抽卡数据为 UIGF 格式
    // ------------------------------------------------------------------

    fun exportGachaData(callback: (String) -> Unit) {
        viewModelScope.launch {
            val uid = authRepository.getUid()
            if (uid.isNullOrBlank()) {
                _importMessage.value = "未登录，无法导出"
                return@launch
            }
            val json = gachaDataImporter.exportToString(uid)
            val fileName = run {
                val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                val timeStr = sdf.format(java.util.Date())
                "UIGF_v3.0_${uid}_${timeStr}.json"
            }
            try {
                // 使用 MediaStore 写入 Download 目录，兼容 Android 10+
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                    }
                }
                val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    android.provider.MediaStore.Files.getContentUri("external")
                }
                val uri = resolver.insert(collection, values)
                if (uri == null) {
                    _importMessage.value = "导出失败：无法创建文件"
                    return@launch
                }
                resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: run {
                        _importMessage.value = "导出失败：无法写入文件"
                        return@launch
                    }
                _importMessage.value = "导出成功: 已保存到 Download/$fileName"
                callback(fileName)
            } catch (e: Exception) {
                _importMessage.value = "导出失败: ${e.message}"
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            val uid = authRepository.getUid()
            val account = gachaRepository.getAccountByUid(uid ?: "")
            if (account != null) {
                gachaRepository.deleteAccount(account.id)
            }
            authRepository.logout()
            // logout 同时删除了账号与全部抽卡数据，需同时通知数据已清除，
            // 否则仅监听 DataCleared 的逻辑无法被触发（语义上数据确实被清了）。
            sessionEventBus.emit(SessionEvent.LogoutCompleted)
            sessionEventBus.emit(SessionEvent.DataCleared)
            loadSettings()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            val uid = authRepository.getUid()
            val account = gachaRepository.getAccountByUid(uid ?: "")
            if (account != null) {
                gachaRepository.deleteAllByAccount(account.id)
            }
            sessionEventBus.emit(SessionEvent.DataCleared)
            loadSettings()
        }
    }
}
