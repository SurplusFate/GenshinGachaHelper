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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.genshin.gachahelper.BuildConfig
import com.genshin.gachahelper.auth.AppLog
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
