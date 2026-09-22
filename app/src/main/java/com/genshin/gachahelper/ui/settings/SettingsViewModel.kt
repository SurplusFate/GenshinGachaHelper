package com.genshin.gachahelper.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.auth.AppLog
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.backup.BackupTrigger
import com.genshin.gachahelper.backup.GachaBackupManager
import com.genshin.gachahelper.backup.WebDavConfig
import com.genshin.gachahelper.backup.WebDavConfigStore
import com.genshin.gachahelper.backup.WebDavResult
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.reminder.ReminderConfig
import com.genshin.gachahelper.reminder.ResinReminderManager
import com.genshin.gachahelper.reminder.ResinReminderStore
import com.genshin.gachahelper.signin.SignInRepository
import com.genshin.gachahelper.sync.GachaDataImporter
import com.genshin.gachahelper.ui.logexport.LogExportDialogState
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
    private val signInRepository: SignInRepository,
    // 2026-09-21：WebDAV 备份配置读取与备份触发
    private val webDavConfigStore: WebDavConfigStore,
    private val backupManager: GachaBackupManager,
    // 2026-09-20：日志导出功能从首页迁至「设置 → 关于」区块（测试期诊断入口收编）
    private val appLog: AppLog,
    // 2026-09-22：树脂/洞天宝钱阈值提醒（设置页为规则主体入口）
    private val reminderStore: ResinReminderStore,
    private val reminderManager: ResinReminderManager,
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

    // 2026-09-20：每日自动签到的 UI 入口（dailySignEnabled/dailySignResult/
    // setDailySignEnabled/manualDailySignIn）已整体迁至便笺页 HomeViewModel，
    // 此处不再暴露。signInRepository 注入保留——logout 时仍需 onLogout()
    // 关闭自动签到任务。

    // ------------------------------------------------------------------
    // WebDAV 抽卡记录备份（2026-09-21 新增）
    // ------------------------------------------------------------------

    /** WebDAV 配置（输入即落库，UI 直接绑定） */
    val webDavConfig: StateFlow<WebDavConfig> = webDavConfigStore.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WebDavConfig()
        )

    /** 测试连接 / 手动备份的即时反馈 */
    private val _webDavMessage = MutableStateFlow<String?>(null)
    val webDavMessage: StateFlow<String?> = _webDavMessage.asStateFlow()

    /** 动作进行中标记（用于禁用按钮，防重复触发） */
    private val _webDavBusy = MutableStateFlow(false)
    val webDavBusy: StateFlow<Boolean> = _webDavBusy.asStateFlow()

    // ------------------------------------------------------------------
    // 树脂 / 洞天宝钱阈值提醒（2026-09-22 新增）
    // ------------------------------------------------------------------

    /** 提醒配置：设置页滑杆与便笺卡片快捷入口读写的是同一份持久化数据 */
    val reminderConfig: StateFlow<ReminderConfig> = reminderStore.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReminderConfig()
        )

    fun setReminderEnabled(enabled: Boolean) = updateReminder { it.copy(enabled = enabled) }

    fun setResinThreshold(threshold: Int) = updateReminder {
        it.copy(resinThreshold = threshold.coerceIn(1, ReminderConfig.DEFAULT_RESIN_MAX))
    }

    fun setHomeCoinEnabled(enabled: Boolean) = updateReminder { it.copy(homeCoinEnabled = enabled) }

    /**
     * 洞天宝钱每小时产量档位。
     *
     * 接口不返回洞天仙力等级，产量只能由用户按游戏内档位选择；档位直接决定
     * 便笺「距上限」倒计时（批次 = 缺口 / 每小时产量）。
     */
    fun setHomeCoinPerHour(perHour: Int) = updateReminder {
        it.copy(
            homeCoinPerHour = perHour.coerceIn(
                ReminderConfig.MIN_HOME_COIN_PER_HOUR,
                ReminderConfig.MAX_HOME_COIN_PER_HOUR
            )
        )
    }

    fun setHomeCoinThreshold(threshold: Int) = updateReminder {
        it.copy(homeCoinThreshold = threshold.coerceIn(1, ReminderConfig.DEFAULT_HOME_COIN_MAX))
    }

    fun setReminderOncePerDay(oncePerDay: Boolean) = updateReminder { it.copy(oncePerDay = oncePerDay) }

    /** 落库后立即按新阈值重排闹钟（关闭开关时 [ResinReminderManager.reschedule] 内部会先取消） */
    private fun updateReminder(transform: (ReminderConfig) -> ReminderConfig) {
        viewModelScope.launch { reminderManager.update(transform) }
    }

    fun setWebDavEnabled(enabled: Boolean) {
        viewModelScope.launch { webDavConfigStore.setEnabled(enabled) }
    }

    fun setWebDavUrl(url: String) {
        viewModelScope.launch { webDavConfigStore.setUrl(url) }
    }

    fun setWebDavUsername(username: String) {
        viewModelScope.launch { webDavConfigStore.setUsername(username) }
    }

    fun setWebDavPassword(password: String) {
        viewModelScope.launch { webDavConfigStore.setPassword(password) }
    }

    fun setWebDavRemoteDir(dir: String) {
        viewModelScope.launch { webDavConfigStore.setRemoteDir(dir) }
    }

    /** 用当前配置探测 WebDAV 服务器连通性与鉴权 */
    fun testWebDavConnection() {
        if (_webDavBusy.value) return
        viewModelScope.launch {
            _webDavBusy.value = true
            _webDavMessage.value = "正在测试连接…"
            val result = runCatching { backupManager.testConnection() }
                .getOrElse { WebDavResult.Failure(it.message ?: "未知错误") }
            _webDavMessage.value = when (result) {
                is WebDavResult.Success -> result.message
                is WebDavResult.Failure -> "失败：${result.message}"
            }
            _webDavBusy.value = false
        }
    }

    /** 立即备份一次（不受自动备份开关限制） */
    fun backupNowByWebDav() {
        if (_webDavBusy.value) return
        viewModelScope.launch {
            _webDavBusy.value = true
            _webDavMessage.value = "正在备份…"
            val result = runCatching { backupManager.backupNow(BackupTrigger.MANUAL) }
                .getOrElse { WebDavResult.Failure(it.message ?: "未知错误") }
            _webDavMessage.value = when (result) {
                is WebDavResult.Success -> result.message
                is WebDavResult.Failure -> "失败：${result.message}"
            }
            _webDavBusy.value = false
        }
    }

    // ------------------------------------------------------------------
    // 日志导出（2026-09-20 从 HomeViewModel 迁入，入口在「关于」区块）
    // ------------------------------------------------------------------

    /** 日志导出弹窗状态：非 null 时底部弹出，提供分享文件/复制全文/复制路径 */
    private val _logExportDialog = MutableStateFlow<LogExportDialogState?>(null)
    val logExportDialog: StateFlow<LogExportDialogState?> = _logExportDialog.asStateFlow()

    /**
     * 打开日志导出弹窗。
     * 同步预生成一份 export 文件到 cacheDir（避免分享时卡顿），把元信息放进状态。
     */
    fun openLogExportDialog() {
        val logDirPath = appLog.logDirPath()
        val activeFile = appLog.activeLogFilePath()
        val exportFile = runCatching { appLog.exportToCache() }.getOrNull()
        AppLog.i(
            "Settings", "openLogExportDialog",
            "dir=$logDirPath active=$activeFile export=${exportFile?.absolutePath} " +
                "size=${exportFile?.length() ?: 0}"
        )
        _logExportDialog.value = LogExportDialogState(
            logDirPath = logDirPath,
            activeFilePath = activeFile,
            exportFilePath = exportFile?.absolutePath,
            exportFileSize = exportFile?.length() ?: 0L
        )
    }

    fun dismissLogExportDialog() {
        _logExportDialog.value = null
    }

    /**
     * 把日志原文复制到系统剪贴板，让用户直接粘贴到 IM。
     */
    fun copyLogToClipboard() {
        val text = runCatching { appLog.copyToClipboard() }.getOrElse { e ->
            AppLog.e("Settings", "copyLogToClipboard", "复制失败: ${e.message}", e)
            ""
        }
        val dialog = _logExportDialog.value
        _logExportDialog.value = dialog?.copy(
            lastAction = if (text.isNotEmpty())
                "已复制 ${text.length} 字符到剪贴板，可粘贴到聊天窗口"
            else
                "复制失败"
        )
    }

    /**
     * 把日志目录 + 当前文件路径复制到剪贴板，用户粘给开发者远程诊断。
     *
     * 2026-09-20 迁移修复：旧实现（HomeViewModel）误调 appLog.copyToClipboard(label)，
     * 实际复制的是日志全文而非路径信息——这里直接写 ClipboardManager 复制路径文本。
     */
    fun revealLogPathInClipboard() {
        val dialog = _logExportDialog.value ?: return
        val text = """
            |GenshinGachaHelper 日志文件
            |日志目录: ${dialog.logDirPath}
            |今日文件: ${dialog.activeFilePath}
            |导出文件: ${dialog.exportFilePath ?: "(未生成)"}
            |导出大小: ${dialog.exportFileSize} 字节
        """.trimMargin()
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("gacha_helper_paths", text)
            )
        }
        AppLog.i("Settings", "revealLogPath", "dir=${dialog.logDirPath}")
        _logExportDialog.value = dialog.copy(
            lastAction = "日志路径已复制到剪贴板，可粘贴发给开发者"
        )
    }

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

            // UID 显示优先级：
            // 1. 已登录 → 直接使用登录 UID（不依赖 AccountEntity 是否存在）
            // 2. 未登录但有本地数据 → 使用本地数据 UID
            // 3. 未登录且无数据 → null
            val account = gachaRepository.getActiveAccount(authUid)
            val hasData = account != null
            val displayUid = when {
                loggedIn -> authUid  // 已登录：直接用登录 UID，不依赖是否有抽卡数据
                hasData -> account?.uid  // 未登录：用本地数据 UID
                else -> null
            }

            _uiState.value = SettingsUiState(
                isLoggedIn = loggedIn,
                uid = displayUid,
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
                // 获取当前登录 UID 和本地数据 UID，传给导入器做校验
                val authUid = if (authRepository.isLoggedIn()) authRepository.getUid() else null
                val localAccount = gachaRepository.getActiveAccount(authUid)
                val localDataUid = localAccount?.uid

                val result = gachaDataImporter.importFromUri(uri, authUid, localDataUid)
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
            // 退出登录 = 关闭自动签到（取消周期任务 + 清通知）+ 清登录凭证 + 清空本地抽卡数据。
            // 数据必须一次性清干净：否则历史页 / 统计页会继续展示上一个账号的记录。
            signInRepository.onLogout()
            // 退出登录后不保留上一个账号的便笺提醒闹钟（快照也会在首页被清掉）
            reminderManager.cancel()
            authRepository.logout()
            gachaRepository.clearUserData()
            sessionEventBus.emit(SessionEvent.LogoutCompleted)
            loadSettings()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            // 清除全部本地抽卡数据（记录 + 账号），保证各页面无残留
            gachaRepository.clearUserData()
            sessionEventBus.emit(SessionEvent.DataCleared)
            loadSettings()
        }
    }
}
