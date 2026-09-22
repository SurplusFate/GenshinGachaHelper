package com.genshin.gachahelper.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.genshin.gachahelper.BuildConfig
import com.genshin.gachahelper.auth.AppLog
import com.genshin.gachahelper.reminder.ReminderConfig
import com.genshin.gachahelper.ui.dockContentBottomPadding
import com.genshin.gachahelper.ui.GlassSurface
import com.genshin.gachahelper.ui.logexport.LogExportDialog
import com.genshin.gachahelper.ui.logexport.shareLogFileViaIntent
import com.genshin.gachahelper.ui.theme.ThemeMode
import java.io.File

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val importMessage by viewModel.importMessage.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    // 日志导出弹窗（2026-09-20 从首页迁入，入口在下方「关于」区块）
    val logExportDialog by viewModel.logExportDialog.collectAsState()
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // ---- WebDAV 备份状态与输入草稿（2026-09-21 新增）----
    // 输入草稿：TextField 绑草稿即时回显，同时写库，避免 DataStore 回读延迟导致丢字
    val webDavConfig by viewModel.webDavConfig.collectAsState()
    val webDavMessage by viewModel.webDavMessage.collectAsState()
    val webDavBusy by viewModel.webDavBusy.collectAsState()
    var webDavUrlDraft by remember { mutableStateOf("") }
    var webDavUserDraft by remember { mutableStateOf("") }
    var webDavPasswordDraft by remember { mutableStateOf("") }
    var webDavDirDraft by remember { mutableStateOf("") }
    // 已保存配置首次就绪后回填一次草稿
    LaunchedEffect(Unit) {
        val cfg = viewModel.webDavConfig.value
        webDavUrlDraft = cfg.url
        webDavUserDraft = cfg.username
        webDavPasswordDraft = cfg.password
        webDavDirDraft = cfg.remoteDir
    }

    // ---- 树脂提醒（2026-09-22 新增）----
    val reminderConfig by viewModel.reminderConfig.collectAsState()
    // 滑杆草稿：拖动中只改本地状态，松手才落库，避免拖动过程高频写 DataStore
    var reminderResinDraft by remember { mutableStateOf(reminderConfig.resinThreshold.toFloat()) }
    var reminderCoinDraft by remember { mutableStateOf(reminderConfig.homeCoinThreshold.toFloat()) }
    LaunchedEffect(reminderConfig.resinThreshold) {
        reminderResinDraft = reminderConfig.resinThreshold.toFloat()
    }
    LaunchedEffect(reminderConfig.homeCoinThreshold) {
        reminderCoinDraft = reminderConfig.homeCoinThreshold.toFloat()
    }
    // Android 13+ 通知权限：开启提醒时才需要（未授权时提醒只能静默失败）
    val settingsContext = LocalContext.current
    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 授权结果不阻塞开关流程 */ }
    val requestReminderPermissionIfNeeded = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                settingsContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            reminderPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 抽卡数据导入文件选择器
    val gachaDataPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importGachaData(it) }
    }

    // 2026-09-20：每日签到入口（开关/立即签到/通知权限申请）已整体迁至
    // 便笺页（HomeScreen 的 DailySignInCard），设置页不再保留。

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 2026-09-20：页面顶栏已移除，顶部需自行避让系统状态栏
            .statusBarsPadding()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + dockContentBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 导入结果提示
        importMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.contains("成功"))
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // 账号管理
        SettingsSection(title = "账号管理") {
            if (uiState.isLoggedIn) {
                // 已登录：直接显示登录 UID（即使没有抽卡数据也正常显示）
                Text(text = "UID: ${uiState.uid ?: "未知"}")
                if (uiState.nickname != null) {
                    Text(
                        text = "昵称: ${uiState.nickname}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!uiState.hasData) {
                    Text(
                        text = "尚未导入/同步抽卡数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("退出登录")
                }
            } else if (uiState.hasData) {
                Text(text = "UID: ${uiState.uid ?: "未知"}")
                Text(
                    text = "本地导入数据（未登录）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "未登录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 主题设置（替代原无用的接口配置）
        SettingsSection(title = "主题设置") {
            Column(Modifier.selectableGroup()) {
                ThemeModeOption(
                    label = "跟随系统",
                    description = "与系统夜间模式保持一致",
                    selected = themeMode == ThemeMode.FOLLOW_SYSTEM,
                    onClick = { viewModel.setThemeMode(ThemeMode.FOLLOW_SYSTEM) }
                )
                ThemeModeOption(
                    label = "白天模式",
                    description = "浅色主题，不受系统设置影响",
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) }
                )
                ThemeModeOption(
                    label = "夜间模式",
                    description = "深色主题，更护眼",
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { viewModel.setThemeMode(ThemeMode.DARK) }
                )
            }
        }

        // 数据管理（导入/导出/清除）
        // （每日签到区块已于 2026-09-20 迁至便笺页）
        SettingsSection(title = "数据管理") {
            // 导入历史数据
            OutlinedButton(
                onClick = { gachaDataPickerLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导入抽卡记录 (UIGF)")
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 导出历史数据
            OutlinedButton(
                onClick = { viewModel.exportGachaData { } },
                enabled = uiState.hasData,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导出抽卡记录 (UIGF)")
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 清除数据
            OutlinedButton(
                onClick = { showClearDataDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除所有抽卡数据")
            }
        }

        // 树脂 / 洞天宝钱阈值提醒（2026-09-22 新增）
        SettingsSection(title = "树脂提醒") {
            Text(
                text = "自定义树脂达到多少后提醒，不必只等回满。到达时刻由便笺快照 + 恢复速率" +
                    "本地推算，交给系统闹钟触发，App 被划掉也能按时响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "开启提醒")
                    Text(
                        text = "达到阈值时发系统通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = reminderConfig.enabled,
                    onCheckedChange = {
                        viewModel.setReminderEnabled(it)
                        if (it) requestReminderPermissionIfNeeded()
                    }
                )
            }

            if (reminderConfig.enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "树脂阈值：${reminderResinDraft.toInt()}",
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = reminderResinDraft,
                    onValueChange = { reminderResinDraft = it },
                    onValueChangeFinished = {
                        viewModel.setResinThreshold(reminderResinDraft.toInt())
                    },
                    valueRange = 1f..ReminderConfig.DEFAULT_RESIN_MAX.toFloat()
                )
                Text(
                    text = "上限 ${ReminderConfig.DEFAULT_RESIN_MAX}（设到上限即回满提醒）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "同时提醒洞天宝钱")
                    }
                    Switch(
                        checked = reminderConfig.homeCoinEnabled,
                        onCheckedChange = { viewModel.setHomeCoinEnabled(it) }
                    )
                }
                if (reminderConfig.homeCoinEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "洞天宝钱阈值：${reminderCoinDraft.toInt()}",
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = reminderCoinDraft,
                        onValueChange = { reminderCoinDraft = it },
                        onValueChangeFinished = {
                            viewModel.setHomeCoinThreshold(reminderCoinDraft.toInt())
                        },
                        valueRange = 1f..ReminderConfig.DEFAULT_HOME_COIN_MAX.toFloat()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "每天只提醒一次")
                        Text(
                            text = "关闭后每次达到阈值都会提醒（10 分钟内不重复）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = reminderConfig.oncePerDay,
                        onCheckedChange = { viewModel.setReminderOncePerDay(it) }
                    )
                }
            }
        }

        // WebDAV 备份（2026-09-21 新增）
        SettingsSection(title = "WebDAV 备份") {
            Text(
                text = "把抽卡记录备份到自己的 WebDAV（坚果云 / Nextcloud / 群晖 / Alist 等）。" +
                    "开启后，每次成功获取最新抽卡记录都会自动备份一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "同步后自动备份")
                    Text(
                        text = "每次获取最新抽卡记录后自动上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = webDavConfig.enabled,
                    onCheckedChange = { viewModel.setWebDavEnabled(it) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webDavUrlDraft,
                onValueChange = {
                    webDavUrlDraft = it
                    viewModel.setWebDavUrl(it)
                },
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webDavUserDraft,
                onValueChange = {
                    webDavUserDraft = it
                    viewModel.setWebDavUsername(it)
                },
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webDavPasswordDraft,
                onValueChange = {
                    webDavPasswordDraft = it
                    viewModel.setWebDavPassword(it)
                },
                label = { Text("密码 / 应用授权码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webDavDirDraft,
                onValueChange = {
                    webDavDirDraft = it
                    viewModel.setWebDavRemoteDir(it)
                },
                label = { Text("远端目录") },
                placeholder = { Text("/GenshinGachaHelper") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "提示：Nextcloud / 坚果云需在网页端生成「应用密码」；局域网 http 地址也支持。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.testWebDavConnection() },
                    enabled = !webDavBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试连接")
                }
                OutlinedButton(
                    onClick = { viewModel.backupNowByWebDav() },
                    enabled = !webDavBusy && uiState.hasData,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (webDavBusy) "处理中…" else "立即备份")
                }
            }

            webDavMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.startsWith("失败")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (webDavConfig.lastBackupTime > 0) {
                    "上次备份：${formatBackupTime(webDavConfig.lastBackupTime)}" +
                        if (webDavConfig.lastBackupStatus.isNotBlank())
                            " · ${webDavConfig.lastBackupStatus}" else ""
                } else {
                    "尚未备份"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 关于（2026-09-20 新增：版本信息 + 测试期诊断用的日志导出入口，
        // 该功能原在首页右上角浮动按钮，现收编进设置页）
        SettingsSection(title = "关于") {
            Text(
                text = "当前版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "项目仓库: github.com/SurplusFate/GenshinGachaHelper",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "非官方工具，数据仅存本地；米游社登录凭证只用于同步抽卡记录与每日便笺。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 导出日志（诊断用）：弹窗提供 分享文件 / 复制全文 / 复制路径
            OutlinedButton(
                onClick = { viewModel.openLogExportDialog() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导出日志（诊断用）")
            }
        }
    }

    // 确认对话框
    if (showClearDataDialog) {
        ConfirmDialog(
            title = "确认清除",
            message = "确定要清除所有抽卡数据吗？此操作不可恢复。",
            onConfirm = {
                viewModel.clearAllData()
                showClearDataDialog = false
            },
            onDismiss = { showClearDataDialog = false }
        )
    }

    if (showLogoutDialog) {
        ConfirmDialog(
            title = "确认退出",
            message = "退出登录将同时清除本地抽卡数据、关闭每日自动签到，确定要退出吗？",
            onConfirm = {
                viewModel.logout()
                showLogoutDialog = false
            },
            onDismiss = { showLogoutDialog = false }
        )
    }

    // 日志导出弹窗（2026-09-20 从首页迁入）：分享动作由 UI 层拿 Context 调起
    logExportDialog?.let { dialog ->
        LogExportDialog(
            dialog = dialog,
            onDismiss = { viewModel.dismissLogExportDialog() },
            onShareFile = { ctx ->
                val path = dialog.exportFilePath
                if (path == null || !File(path).exists()) {
                    // 缓存的导出文件被系统清理掉了 → 重新生成一份
                    viewModel.openLogExportDialog()
                    return@LogExportDialog
                }
                AppLog.i(
                    "Settings", "shareLogFile(UI)",
                    "file=$path size=${File(path).length()}"
                )
                shareLogFileViaIntent(ctx, path)
            },
            onCopyText = { viewModel.copyLogToClipboard() },
            onOpenPath = { viewModel.revealLogPathInClipboard() }
        )
    }
}

/** 三选一主题选项：带 Radio + 标题 + 说明 */
@Composable
private fun ThemeModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = label, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * WebDAV 上次备份时间的展示格式化（2026-09-21 新增）
 */
private fun formatBackupTime(timestamp: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
