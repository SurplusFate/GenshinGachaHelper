package com.genshin.gachahelper.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.genshin.gachahelper.BuildConfig
import com.genshin.gachahelper.auth.AppLog
import com.genshin.gachahelper.reminder.ReminderConfig
import com.genshin.gachahelper.reminder.ResinReminderNotifier
import com.genshin.gachahelper.ui.dockContentBottomPadding
import com.genshin.gachahelper.ui.GlassSurface
import com.genshin.gachahelper.ui.logexport.LogExportDialog
import com.genshin.gachahelper.ui.logexport.shareLogFileViaIntent
import com.genshin.gachahelper.ui.theme.ThemeMode
import java.io.File

/**
 * 设置页。
 *
 * 2026-09-23 性能改造（用户反馈"设置界面有点卡"）：
 *  1. 状态读取下沉：每个区块是独立 Composable，各自 collect 自己需要的 Flow。
 *     此前所有状态都 collect 在页面顶层 —— 输入 WebDAV 地址、拖动阈值滑杆时，
 *     每帧都会触发**整页**重组（7 张液态玻璃卡片的 drawBackdrop 链全部重建），
 *     现在只有对应区块重组；
 *  2. 滑动草稿下沉到滑杆自己的 Composable 内，拖动过程不波及所在区块；
 *  3. 长列表改用 LazyColumn：此前 `Column + verticalScroll` 会组合全部区块，
 *     液态玻璃卡片要做实时背景模糊采样，同时在场数量越多滚动越吃 GPU，
 *     现在只组合视口内的区块。
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val importMessage by viewModel.importMessage.collectAsState()
    // 日志导出弹窗（2026-09-20 从首页迁入，入口在下方「关于」区块）
    val logExportDialog by viewModel.logExportDialog.collectAsState()

    // 文件选择 / 权限申请 launcher 一律放在页面级：LazyColumn 的条目滚出屏幕会被回收，
    // 若 launcher 建在条目内部，系统弹窗返回时回调可能随条目一起被注销。
    val gachaDataPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importGachaData(it) }
    }
    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 授权结果不阻塞开关流程 */ }

    // Android 13+ 通知权限：开启提醒时才需要（未授权时提醒只能静默失败）
    val requestReminderPermission: () -> Unit = remember(reminderPermissionLauncher, context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val pickGachaFile: () -> Unit = remember(gachaDataPickerLauncher) {
        { gachaDataPickerLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 2026-09-22：状态栏避让由 GachaAppNavHost 的 Scaffold innerPadding 统一承担，
        // 此处再 statusBarsPadding() 属重复避让，顶部凭空多空一条。
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 16.dp + dockContentBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 导入结果提示
        importMessage?.let { message ->
            item(key = "import_message") {
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
        }

        item(key = "account") { AccountSection(viewModel) }
        item(key = "theme") { ThemeSection(viewModel) }
        item(key = "data") { DataSection(viewModel, onPickFile = pickGachaFile) }
        item(key = "reminder") {
            ReminderSection(viewModel, requestReminderPermission = requestReminderPermission)
        }
        item(key = "reminder_check") {
            ReminderDiagnosticsSection(
                viewModel,
                requestReminderPermission = requestReminderPermission
            )
        }
        item(key = "webdav") { WebDavSection(viewModel) }
        item(key = "about") { AboutSection(viewModel) }
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

// ---------------------------------------------------------------------------
// 账号管理
// ---------------------------------------------------------------------------

@Composable
private fun AccountSection(viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }

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
}

// ---------------------------------------------------------------------------
// 主题设置
// ---------------------------------------------------------------------------

@Composable
private fun ThemeSection(viewModel: SettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsState()

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
}

// ---------------------------------------------------------------------------
// 数据管理（导入 / 导出 / 清除）
// ---------------------------------------------------------------------------

@Composable
private fun DataSection(viewModel: SettingsViewModel, onPickFile: () -> Unit) {
    // 只有"导出/清除"按钮的可点状态依赖账号数据，避免把整页都绑上 uiState
    val uiState by viewModel.uiState.collectAsState()
    var showClearDataDialog by remember { mutableStateOf(false) }

    SettingsSection(title = "数据管理") {
        // 导入历史数据
        OutlinedButton(
            onClick = onPickFile,
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
}

// ---------------------------------------------------------------------------
// 树脂 / 洞天宝钱阈值提醒（2026-09-22 新增）
// ---------------------------------------------------------------------------

@Composable
private fun ReminderSection(
    viewModel: SettingsViewModel,
    requestReminderPermission: () -> Unit
) {
    val reminderConfig by viewModel.reminderConfig.collectAsState()

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
                    if (it) requestReminderPermission()
                }
            )
        }

        if (reminderConfig.enabled) {
            Spacer(modifier = Modifier.height(12.dp))
            ReminderThresholdSlider(
                label = "树脂阈值",
                value = reminderConfig.resinThreshold,
                valueRange = 1..ReminderConfig.DEFAULT_RESIN_MAX,
                onCommit = { viewModel.setResinThreshold(it) }
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
                ReminderThresholdSlider(
                    label = "洞天宝钱阈值",
                    value = reminderConfig.homeCoinThreshold,
                    valueRange = 1..ReminderConfig.DEFAULT_HOME_COIN_MAX,
                    onCommit = { viewModel.setHomeCoinThreshold(it) }
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

        // 洞天宝钱产量档位（2026-09-22 新增）：决定便笺「距上限」倒计时，
        // 与提醒开关无关，故独立于上面的 if 分支，始终可改。
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "洞天宝钱产量：${reminderConfig.homeCoinPerHour} 个/小时",
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "按游戏里「洞天仙力」档位选。接口不返回仙力等级，只能手动定；" +
                "满仙力 20000 = 30 个/小时，选小了「距上限」会显示得偏长。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReminderConfig.HOME_COIN_PER_HOUR_OPTIONS.forEach { rate ->
                if (reminderConfig.homeCoinPerHour == rate) {
                    Button(onClick = { viewModel.setHomeCoinPerHour(rate) }) {
                        Text(text = "$rate")
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.setHomeCoinPerHour(rate) }) {
                        Text(text = "$rate")
                    }
                }
            }
        }
    }
}

/**
 * 阈值滑杆：拖动期间只改本 Composable 内的草稿，松手才落库。
 *
 * 草稿刻意放在这里而不是调用方——放在区块里时，每帧拖动都会把整个「树脂提醒」
 * 区块（含开关、档位按钮行）重新组合一遍，是设置页最明显的一处掉帧来源。
 */
@Composable
private fun ReminderThresholdSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    onCommit: (Int) -> Unit
) {
    var draft by remember { mutableStateOf(value.toFloat()) }
    // 外部值变化（例如别的入口改了阈值）时同步草稿，但不打断正在进行的拖动
    LaunchedEffect(value) {
        if (draft.toInt() != value) draft = value.toFloat()
    }

    Text(
        text = "$label：${draft.toInt()}",
        fontWeight = FontWeight.Bold
    )
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onCommit(draft.toInt()) },
        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    )
}

// ---------------------------------------------------------------------------
// 提醒自检（2026-09-23 新增）：权限 / 渠道 / 精确闹钟 / 电池优化 / 已排时刻
// ---------------------------------------------------------------------------

@Composable
private fun ReminderDiagnosticsSection(
    viewModel: SettingsViewModel,
    requestReminderPermission: () -> Unit
) {
    val reminderDiagnostics by viewModel.reminderDiagnostics.collectAsState()
    val reminderTestMessage by viewModel.reminderTestMessage.collectAsState()
    val context = LocalContext.current

    SettingsSection(title = "提醒自检") {
        Text(
            text = "判断「为什么没收到提醒」用。系统会在省电模式或划掉后台时清掉闹钟，" +
                "把本应用加入电池优化白名单，提醒才稳定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        val diagnostics = reminderDiagnostics
        if (diagnostics == null) {
            Text(
                text = "正在检测…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ReminderCheckRow(
                title = "通知权限",
                ok = diagnostics.notificationPermissionGranted,
                hint = "Android 13+ 需允许通知，否则发送被静默丢弃",
                actionLabel = "去授权",
                onAction = requestReminderPermission
            )
            ReminderCheckRow(
                title = "通知渠道",
                ok = diagnostics.channelEnabled,
                hint = "「树脂提醒」渠道被系统关闭时通知不显示",
                actionLabel = "去开启",
                onAction = { openReminderChannelSettings(context) }
            )
            ReminderCheckRow(
                title = "精确闹钟",
                ok = diagnostics.exactAlarmAllowed,
                hint = "未授予时降级为不精确闹钟，提醒可能延迟",
                actionLabel = "去授权",
                onAction = { openExactAlarmSettings(context) }
            )
            ReminderCheckRow(
                title = "电池优化白名单",
                ok = diagnostics.batteryOptimizationIgnored,
                hint = "未加入时系统会限制后台闹钟（划掉后台即失效）",
                actionLabel = "去关闭",
                onAction = { requestIgnoreBatteryOptimization(context) }
            )
            ReminderCheckRow(
                title = "本地便笺数据",
                ok = diagnostics.hasSnapshot,
                hint = if (diagnostics.hasSnapshot) {
                    "快照时间：${formatReminderTime(diagnostics.snapshotAt)}"
                } else {
                    "无快照，无法推算提醒时刻（先登录并刷新一次便笺）"
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (diagnostics.nextTriggerAt > 0L) {
                    "已排下次提醒：${formatReminderTime(diagnostics.nextTriggerAt)}"
                } else {
                    "当前没有已排的提醒（开关关闭 / 无快照 / 已达阈值且今天已提醒）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.sendTestReminderNotification() }) {
                Text(text = "发送测试通知")
            }
            OutlinedButton(onClick = { viewModel.refreshReminderDiagnostics() }) {
                Text(text = "重新检测")
            }
        }
        reminderTestMessage?.let { message ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// WebDAV 备份（2026-09-21 新增）
// ---------------------------------------------------------------------------

@Composable
private fun WebDavSection(viewModel: SettingsViewModel) {
    // 输入草稿：TextField 绑草稿即时回显，同时写库，避免 DataStore 回读延迟导致丢字
    val webDavConfig by viewModel.webDavConfig.collectAsState()
    val webDavMessage by viewModel.webDavMessage.collectAsState()
    val webDavBusy by viewModel.webDavBusy.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
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
}

// ---------------------------------------------------------------------------
// 关于（2026-09-20 新增：版本信息 + 测试期诊断用的日志导出入口）
// ---------------------------------------------------------------------------

@Composable
private fun AboutSection(viewModel: SettingsViewModel) {
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

// ---------------------------------------------------------------------------
// 提醒自检（2026-09-23 新增）
// ---------------------------------------------------------------------------

/**
 * 提醒自检的单行：标题 + 说明 + 右侧状态（异常时给一个跳系统设置的入口）。
 */
@Composable
private fun ReminderCheckRow(
    title: String,
    ok: Boolean,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!ok && actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(text = actionLabel) }
        } else {
            Text(
                text = if (ok) "正常" else "异常",
                fontWeight = FontWeight.Bold,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 跳「应用通知」系统设置页 */
private fun openReminderChannelSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ResinReminderNotifier.CHANNEL_ID)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 跳「闹钟和提醒」授权页（Android 12+ 才有该页面） */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 请求把本应用加入电池优化白名单（关闭电池优化） */
private fun requestIgnoreBatteryOptimization(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 提醒时刻展示格式化 */
private fun formatReminderTime(timestamp: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
