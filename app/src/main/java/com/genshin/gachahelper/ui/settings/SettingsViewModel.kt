package com.genshin.gachahelper.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.sync.GachaDataImporter
import com.genshin.gachahelper.ui.theme.ThemeMode
import com.genshin.gachahelper.ui.theme.ThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isLoggedIn: Boolean = false,
    val uid: String? = null,
    val nickname: String? = null,
    val hasData: Boolean = false,
    val errorLogCount: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val gachaRepository: GachaRepository,
    private val gachaDataImporter: GachaDataImporter,
    private val sessionEventBus: SessionEventBus,
    private val themeRepository: ThemeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    /** 当前主题模式：随系统/白天/夜间 */
    val themeMode: StateFlow<ThemeMode> = themeRepository.themeModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.FOLLOW_SYSTEM
        )

    init {
        // 监听全局会话事件：登录/退出/导入/清除后需重新 loadSettings
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
            val authUid = authRepository.getUid()
            val nickname = authRepository.getNickname()

            // 解析活跃账号：登录时用登录UID，未登录时回退到最近导入的账号
            val account = gachaRepository.getActiveAccount(authUid)
            val hasData = account != null

            _uiState.value = SettingsUiState(
                isLoggedIn = loggedIn,
                uid = account?.uid,
                nickname = nickname,
                hasData = hasData
            )
        }
    }

    /** 切换主题模式 */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themeRepository.setThemeMode(mode)
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
            val authUid = authRepository.getUid()
            val account = gachaRepository.getActiveAccount(authUid)
            val exportUid = account?.uid
            if (exportUid.isNullOrBlank()) {
                _importMessage.value = "没有可导出的抽卡数据"
                return@launch
            }
            val json = gachaDataImporter.exportToString(exportUid)
            val fileName = run {
                val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                val timeStr = sdf.format(java.util.Date())
                "UIGF_v3.0_${exportUid}_${timeStr}.json"
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
            // logout 同时删除了账号与全部抽卡数据，需同时通知数据已清除
            sessionEventBus.emit(SessionEvent.LogoutCompleted)
            sessionEventBus.emit(SessionEvent.DataCleared)
            loadSettings()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            val authUid = authRepository.getUid()
            val account = gachaRepository.getActiveAccount(authUid)
            if (account != null) {
                gachaRepository.deleteAllByAccount(account.id)
            }
            sessionEventBus.emit(SessionEvent.DataCleared)
            loadSettings()
        }
    }
}
