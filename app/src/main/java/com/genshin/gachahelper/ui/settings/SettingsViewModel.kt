package com.genshin.gachahelper.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.config.importer.ConfigImporter
import com.genshin.gachahelper.config.store.ConfigStore
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.sync.GachaDataImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.FileOutputStream
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    init {
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
            val fileName = "UIGF_${uid}_${System.currentTimeMillis()}.json"
            val file = java.io.File(context.getExternalFilesDir(null), fileName)
            FileOutputStream(file).use { it.write(json.toByteArray()) }
            _importMessage.value = "导出成功: $fileName"
            callback(file.absolutePath)
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
            loadSettings()
        }
    }
}
