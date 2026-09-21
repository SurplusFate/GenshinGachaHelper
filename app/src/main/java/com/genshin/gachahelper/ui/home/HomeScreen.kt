package com.genshin.gachahelper.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.analysis.LuckConfidence
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.auth.CaptchaData
import com.genshin.gachahelper.auth.DailyNoteData
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.sync.SyncState
import com.genshin.gachahelper.ui.dockContentBottomPadding
import com.genshin.gachahelper.ui.navigation.Screen
import com.genshin.gachahelper.ui.theme.FiveStarColor
import com.genshin.gachahelper.ui.theme.WishEmptyGlow
import com.genshin.gachahelper.ui.theme.WishShapes
import com.genshin.gachahelper.ui.theme.goldGlowBorder
import com.genshin.gachahelper.ui.GlassSurface
import com.genshin.gachahelper.ui.theme.homeHeroCardGradient
import com.genshin.gachahelper.ui.theme.isWishDark
import com.genshin.gachahelper.ui.theme.rememberReduceMotion
import com.genshin.gachahelper.ui.theme.wishCardBg
import com.genshin.gachahelper.ui.theme.wishAccentGold
import com.genshin.gachahelper.ui.theme.wishSuccess
import com.genshin.gachahelper.ui.theme.wishWarning
import android.annotation.SuppressLint
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // 2026-09-20：米游社签到入口从设置页迁到便笺页（用户需求"把自动签到放到便笺来"）
    val dailySignEnabled by viewModel.dailySignEnabled.collectAsState()
    val dailySignResult by viewModel.dailySignResult.collectAsState()

    // Android 13+ 通知权限申请（仅用于展示签到结果，未授权不影响签到）
    val signContext = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 授权结果不阻塞流程 */ }
    val requestNotificationPermissionIfNeeded = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                signContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 1034 风控验证弹窗：便笺被风控拦截时弹出 geetest 滑块
    uiState.captcha?.let { captcha ->
        CaptchaVerifyDialog(
            captcha = captcha,
            onSolved = { validateJson -> viewModel.onCaptchaSolved(validateJson) },
            onDismiss = { viewModel.dismissCaptcha() },
            onError = { msg -> viewModel.reportCaptchaError(msg) }
        )
    }

    // 2026-09-20：日志导出弹窗已迁至「设置 → 关于」区块（测试期诊断入口收编），
    // 首页右上角的「日志」浮动按钮同步移除。

    // 首页背景（2026-09-20 统一化改造）：
    // 不再铺 homeNightBackdrop 纯渐变层——那层会把全局背景的两枚柔光斑
    // 盖住，导致首页与其他页面观感割裂。现在直接透出全局背景画布
    // （深空/浅蓝白渐变 + 柔光斑，与其他 tab 完全同底），
    // 星空画布继续叠加其上——四页唯一差异只剩"首页有星星"。
    Box(modifier = Modifier.fillMaxSize()) {
        // 浅色模式不叠加星空，与全局背景完全一致
        if (isWishDark()) {
            NightSkyCanvas(modifier = Modifier.fillMaxSize())
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = wishAccentGold())
                }
            }

            // 既没登录也没有本地数据 → 引导页
            !uiState.isLoggedIn && !uiState.hasData -> {
                WishEmptyGlow(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "原神抽卡助手",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "授权米游社自动同步，或手动导入 UIGF 数据，分析你的抽卡运气",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { navController.navigate(Screen.Auth.route) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = WishShapes.lg,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE7C877),
                                contentColor = Color(0xFF1A1508)
                            )
                        ) {
                            Text("授权米游社", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.Settings.route) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = WishShapes.lg,
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        ) {
                            Text("导入 UIGF 数据")
                        }
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    // 2026-09-20：页面顶栏已移除，顶部间距需包含系统状态栏高度
                    // （edge-to-edge 下原先由 Scaffold topBar 承担）
                    top = with(androidx.compose.ui.platform.LocalDensity.current) {
                        androidx.compose.foundation.layout.WindowInsets.statusBars
                            .getTop(this).toDp()
                    } + 8.dp,
                    // 底部为悬浮 DOCK 预留空白：最后一张卡片不会被胶囊压住
                    bottom = dockContentBottomPadding() + 12.dp
                )
            ) {
                // 0. 实时状态（树脂 / 洞天宝钱 / 米游社签到）：仅登录且已拿到数据/错误时占位
                if (uiState.isLoggedIn && (uiState.dailyNote != null || uiState.dailyNoteError != null)) {
                    item {
                        DailyNoteCard(
                            uiState = uiState,
                            onRefresh = { viewModel.refreshDailyNote(force = true) },
                            onVerify = { viewModel.startCaptchaVerification() },
                            signEnabled = dailySignEnabled,
                            signResult = dailySignResult,
                            onToggleSign = { enabled ->
                                viewModel.setDailySignEnabled(enabled)
                                if (enabled) requestNotificationPermissionIfNeeded()
                            },
                            onManualSignIn = { viewModel.manualDailySignIn() }
                        )
                    }
                }

                // 1. Hero + 运气环 (方案A + 方案C 融合)
                item { HeroLuckCard(uiState) }

                // 2. 最近出金横滑 (方案B)
                if (uiState.recentFiveStars.isNotEmpty()) {
                    item { SectionHeader("最近出金") }
                    item {
                        RecentFiveStarsRow(
                            records = uiState.recentFiveStars,
                            intervals = uiState.recentFiveStarIntervals
                        )
                    }
                }

                // 3. 保底进度网格 (方案A)
                item { SectionHeader("保底进度") }
                val pools = listOf(
                    "角色池" to uiState.characterStats,
                    "角色池-2" to uiState.character2Stats,
                    "武器池" to uiState.weaponStats,
                    "常驻池" to uiState.standardStats,
                    "新手池" to uiState.noviceStats,
                    "集录池" to uiState.chronicledStats
                )
                items(pools.chunked(2)) { rowPools ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowPools.forEach { (label, stats) ->
                            PityGridCard(
                                label = label,
                                poolStats = stats,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowPools.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 4. 运气拆解 (方案C)
                item { SectionHeader("运气拆解") }
                item { LuckDetailCard(uiState) }

                // 5. 同步按钮
                item { SyncSection(uiState, viewModel, navController) }
            }
        }
    }
}

/** 首页夜空氛围层：顶部鎏金光晕 + 随机星点（仅深色模式显示星点，浅色暖白底不画星） */
@Composable
private fun NightSkyCanvas(modifier: Modifier = Modifier) {
    val dark = isWishDark()
    val stars = remember {
        // 固定种子生成 42 颗星，避免重组随机闪烁
        val seed = 20260908
        var state = seed
        fun next(): Float {
            state = state * 1103515245 + 12345
            return ((state ushr 16) and 0x7fff) / 32767f
        }
        List(42) {
            Triple(next() * 1f, next() * 1f, 0.15f + next() * 0.7f)
        }
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 左上 / 右上两团鎏金氛围光（模仿网页版 radial-gradient）
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFE7C877).copy(alpha = 0.10f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(w * 0.92f, 0f),
                radius = w * 0.9f
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFF4C6FFF).copy(alpha = 0.06f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(0f, h * 0.2f),
                radius = w * 0.75f
            )
        )
        if (dark) {
            stars.forEach { (fx, fy, alpha) ->
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.85f),
                    radius = if (alpha > 0.72f) 1.6f else 1.1f,
                    center = androidx.compose.ui.geometry.Offset(fx * w, fy * h)
                )
            }
        }
    }
}

// ============================ 实时状态（每日便笺） ============================

/** 洞天宝钱主题色（冷青，与树脂的鎏金区分） */
private val HomeCoinColor = Color(0xFF6FC3DF)

/**
 * 实时状态卡片：树脂 / 洞天宝钱。
 *
 * 数据来自米游社每日便笺接口，仅在已登录时展示。
 * 自动刷新受 60 秒节流约束，右上角「刷新」为强制刷新（跳过窗口）。
 * 接口有频率限制，失败时保留上一次成功数据，只在无数据时展示错误文案。
 */
/**
 * 实时状态卡片：便笺数据（树脂/洞天宝钱/委托）+ 米游社签到，一体化展示。
 *
 * 签到区原为设置页功能（2026-09-20 迁至便笺页），先做成独立卡片，
 * 后按用户反馈"合到一块，不用分两个卡片"并入本卡片底部：
 * 分隔线之下一行 = 签到开关 + 「签到」快捷按钮 + 最近结果。
 */
@Composable
private fun DailyNoteCard(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    onVerify: () -> Unit = {},
    signEnabled: Boolean = false,
    signResult: String? = null,
    onToggleSign: (Boolean) -> Unit = {},
    onManualSignIn: () -> Unit = {}
) {
    val note = uiState.dailyNote
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = WishShapes.lg
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "实时状态",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = homeTextHigh()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.dailyNoteLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = wishAccentGold()
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = "刷新",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = wishAccentGold(),
                        modifier = Modifier
                            .clip(WishShapes.pill)
                            .clickable(enabled = !uiState.dailyNoteLoading) { onRefresh() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (note != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DailyNoteStat(
                        label = "树脂",
                        current = note.currentResin,
                        max = note.maxResin,
                        recoverySeconds = note.resinRecoverySeconds,
                        fetchedAt = note.fetchedAt,
                        accent = wishAccentGold(),
                        modifier = Modifier.weight(1f)
                    )
                    DailyNoteStat(
                        label = "洞天宝钱",
                        current = note.currentHomeCoin,
                        max = note.maxHomeCoin,
                        recoverySeconds = note.homeCoinRecoverySeconds,
                        fetchedAt = note.fetchedAt,
                        accent = HomeCoinColor,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "每日委托 ${note.finishedTaskNum}/${note.totalTaskNum}" +
                        " · 周本减半 ${note.remainResinDiscount}/${note.resinDiscountLimit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = homeTextLow()
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.dailyNoteError ?: "暂无数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = wishWarning()
                )
                // 风控验证入口：1034 被拦截、或验证提交失败时都提供重试按钮
                // （验证参数一次性，失败后重新申请一套新参数再试）
                if ((uiState.dailyNoteErrorCode == 1034 || uiState.dailyNoteErrorCode == -1) &&
                    uiState.captcha == null
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onVerify,
                        modifier = Modifier.fillMaxWidth(),
                        shape = WishShapes.pill,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE7C877),
                            contentColor = Color(0xFF1A1508)
                        )
                    ) {
                        Text("完成安全验证", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ─── 米游社签到区（与便笺数据合卡展示，分隔线隔开） ───
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(homeTextLow().copy(alpha = 0.25f))
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "米游社签到",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = homeTextHigh()
                    )
                    signResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.labelSmall,
                            color = homeTextLow()
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (signEnabled) {
                        // 「签到」快捷按钮：与右上「刷新」同款 pill 文字按钮
                        Text(
                            text = "签到",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = wishAccentGold(),
                            modifier = Modifier
                                .clip(WishShapes.pill)
                                .clickable { onManualSignIn() }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Switch(
                        checked = signEnabled,
                        onCheckedChange = onToggleSign
                    )
                }
            }
        }
    }
}

/**
 * 1034 风控验证对话框：WebView 渲染 geetest 滑块。
 *
 * 页面以 webstatic.mihoyo.com 为 base URL（与官方 H5 验证环境一致），
 * 加载 geetest 官方 gt.js，注入 [CaptchaData] 参数初始化滑块；用户
 * 拖动完成后通过 JS bridge（AndroidBridge）回调 geetest 三元组给
 * [onSolved]，由 ViewModel 提交验证并自动重试便笺请求。
 */
@Composable
private fun CaptchaVerifyDialog(
    captcha: CaptchaData,
    onSolved: (validateJson: String) -> Unit,
    onDismiss: () -> Unit,
    onError: ((String) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // 滑块完成后 WebView 可能仍有迟到回调，CAS 保证只提交一次
    val solvedRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            // 弹窗接近全屏：geetest 图片验证码需要足够大的展示区域，
            // 窄弹窗会导致图片被压缩、图标难以辨认
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "安全验证",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF333333)
                    )
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF999999),
                        modifier = Modifier
                            .clip(WishShapes.pill)
                            .clickable { onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "米游社风控校验，请按图片提示完成验证",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888)
                )
                Spacer(modifier = Modifier.height(10.dp))
                AndroidView(
                    // 撑满弹窗剩余空间，把全部高度让给验证组件
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = {
                        WebView(context).apply {
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // ── 环境一致性（2026-09 修复）──────────────────────
                            // 极验 gt.js 会读取 navigator.userAgent 做环境画像，
                            // 并用它请求 api.geetest.com。Android WebView 默认 UA
                            // 带 "wv" 标记（WebView 标识），会被极验判定为
                            // 非浏览器环境而返回降级配置（本地假验证）。
                            // 这里伪装成与米游社 H5 工具一致的移动浏览器 UA。
                            settings.userAgentString = CAPTCHA_UA
                            // gt.js 0.4.9 内部硬编码 http://api.geetest.com，
                            // 而页面 baseUrl 是 https://webstatic.mihoyo.com，
                            // 属混合内容请求，需显式放行（网络安全配置已对
                            // geetest.com 开放明文，两者缺一不可）
                            settings.mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            addJavascriptInterface(
                                object : Any() {
                                    /**
                                     * 验证完成回调。
                                     *
                                     * 不写死字段名：米游社下发的 gt 可能对应 geetest v3
                                     * （geetest_challenge/validate/seccode）或 v4
                                     * （lot_number/pass_token/gen_time/captcha_output），
                                     * 这里把 getValidate() 的原始 JSON 整体回传，
                                     * 由 Kotlin 侧按字段名自动识别版本。
                                     */
                                    @JavascriptInterface
                                    fun onValidated(validateJson: String) {
                                        if (!solvedRef.compareAndSet(false, true)) return
                                        com.genshin.gachahelper.auth.AppLog.i(
                                            "Captcha", "onValidated",
                                            "validateJson=$validateJson"
                                        )
                                        mainHandler.post { onSolved(validateJson) }
                                    }

                                    @JavascriptInterface
                                    fun onCaptchaError(message: String) {
                                        // 组件加载/校验出错：回报错误信息。
                                        // 不关弹窗——让用户看到具体原因后重试或手动关闭，
                                        // 避免"点了没反应"的静默失败。
                                        com.genshin.gachahelper.auth.AppLog.w(
                                            "Captcha", "onCaptchaError",
                                            "message=${message.take(200)}"
                                        )
                                        mainHandler.post {
                                            onError?.invoke("验证组件错误：${message.take(200)}")
                                        }
                                    }

                                    /**
                                     * gt.js 降级检测回调（由注入脚本在初始化后调用）。
                                     *
                                     * 极验在服务端不可达时会切到 failback 模式：界面照常
                                     * 展示、用户也能拖动"通过"，但 getValidate() 返回的是
                                     * 本地假值，提交给米游社必然失败（空 message 的 -1）。
                                     * 这里把降级状态显式回报，便于用户理解失败原因。
                                     */
                                    @JavascriptInterface
                                    fun onCaptchaDegraded(reason: String) {
                                        com.genshin.gachahelper.auth.AppLog.w(
                                            "Captcha", "onCaptchaDegraded",
                                            "reason=$reason"
                                        )
                                        mainHandler.post {
                                            onError?.invoke("验证组件已降级（$reason），结果可能无效")
                                        }
                                    }

                                    /**
                                     * 验证成功时的形态探针（2026-09 定罪修复配套）。
                                     *
                                     * JS 侧对 getValidate() 结果做形态自检：
                                     * - validate_len=32：正常形态（仍需极验二次校验裁决）
                                     * - validate==challenge / 长度异常：failback 假值特征
                                     * 由本回调回传原生侧记日志，配合 onValidated 后的
                                     * 极验预检形成完整证据链。
                                     */
                                    @JavascriptInterface
                                    fun onProbeInfo(probe: String) {
                                        com.genshin.gachahelper.auth.AppLog.i(
                                            "Captcha", "onProbeInfo",
                                            "probe=$probe"
                                        )
                                    }
                                },
                                "AndroidBridge"
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // 页面就绪后注入验证参数启动验证组件。
                                    // 参数经 JSON 序列化后传入，避免 challenge 内的
                                    // 特殊字符破坏 JS 调用（原来裸拼字符串有截断风险）
                                    val payload = com.google.gson.JsonObject().apply {
                                        addProperty("gt", captcha.gt)
                                        addProperty("challenge", captcha.challenge)
                                        addProperty("newCaptcha", captcha.newCaptcha)
                                    }
                                    val js = "startGeetestJson('${payload}')"
                                    com.genshin.gachahelper.auth.AppLog.i(
                                        "Captcha", "start",
                                        "gt=${captcha.gt} challenge=${captcha.challenge.take(20)}... " +
                                            "newCaptcha=${captcha.newCaptcha}"
                                    )
                                    view?.evaluateJavascript(js, null)
                                }

                                /**
                                 * 极验请求协议重写（2026-09 定罪修复）。
                                 *
                                 * gt.0.4.9 loader 及其子验证 JS（fullpage.9.x）内部
                                 * 存在 http:// 明文请求 api.geetest.com 的路径。明文链路
                                 * 在移动网络下易被劫持/注入/静默失败，导致组件降级为
                                 * 本地假验证（极验二次校验 seccode:false）。
                                 *
                                 * 这里把 *.geetest.com 的明文请求统一重写为 https，
                                 * 用 OkHttp 代发并透传 Referer/UA 等请求头，
                                 * 保证极验服务端校验链路全程加密且特征一致。
                                 */
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?
                                ): android.webkit.WebResourceResponse? {
                                    val url = request?.url ?: return super.shouldInterceptRequest(view, request)
                                    if (url.scheme != "http" ||
                                        url.host?.endsWith("geetest.com") != true
                                    ) {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    return try {
                                        // android.net.Uri 的构建器 API 是 buildUpon()（newBuilder 是 okhttp3.HttpUrl 的）；
                                        // OkHttp 的 url() 只收 String/URL/HttpUrl，所以最后 toString()
                                        val httpsUrl = url.buildUpon().scheme("https").build().toString()
                                        val reqBuilder = okhttp3.Request.Builder().url(httpsUrl)
                                        // 透传 WebView 请求头（Referer/UA/Cookie 等）
                                        request.requestHeaders?.forEach { (k, v) ->
                                            if (!v.isNullOrBlank() && !k.equals("Accept-Encoding", true)) {
                                                reqBuilder.addHeader(k, v)
                                            }
                                        }
                                        val resp = captchaHttpClient().newCall(reqBuilder.build()).execute()
                                        com.genshin.gachahelper.auth.AppLog.i(
                                            "Captcha", "intercept",
                                            "http→https ${url.host}${url.path ?: ""} " +
                                                "code=${resp.code}"
                                        )
                                        android.webkit.WebResourceResponse(
                                            resp.header("Content-Type") ?: "application/octet-stream",
                                            null,
                                            resp.body?.byteStream()
                                        )
                                    } catch (e: Exception) {
                                        com.genshin.gachahelper.auth.AppLog.w(
                                            "Captcha", "intercept",
                                            "重写失败 ${url.host}${url.path ?: ""} " +
                                                "err=${e.javaClass.simpleName}:${e.message}"
                                        )
                                        super.shouldInterceptRequest(view, request)
                                    }
                                }

                                /**
                                 * 资源加载失败诊断（2026-09 新增）。
                                 *
                                 * gt.js 需从 static.geetest.com 加载，若该请求失败
                                 * （网络/证书/明文限制），组件会静默降级或直接不可用。
                                 * 这里把失败原因回报给用户，替代原来"点了没反应"。
                                 */
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?
                                ) {
                                    val host = request?.url?.host ?: "?"
                                    val desc = error?.description?.toString() ?: "unknown"
                                    val code = error?.errorCode ?: -1
                                    com.genshin.gachahelper.auth.AppLog.w(
                                        "Captcha", "onReceivedError",
                                        "url=${request?.url} host=$host code=$code desc=$desc"
                                    )
                                    onError?.invoke("资源加载失败：$host（$desc）")
                                }
                            }
                            loadDataWithBaseURL(
                                "https://webstatic.mihoyo.com/",
                                CAPTCHA_HTML,
                                "text/html",
                                "utf-8",
                                null
                            )
                        }
                    },
                    onRelease = { it.destroy() }
                )
            }
        }
    }
}

/**
 * 验证 WebView 专用 UA。
 *
 * ── 2026-09 定罪修复（日志实锤）──────────────────────────────
 * 用户日志显示：滑块完成后极验服务端二次校验返回
 *   {"seccode":"false"}
 * 即 validate 是 WebView 环境里 gt.js 降级模式生成的**本地假值**，
 * 米哈游提交极验必然失败（空 message 的 -1）。
 *
 * 降级诱因之一：旧 UA 里带 "; wv)" 标记——这是嵌入式 WebView 的
 * 显著特征，极验环境检测直接命中并降级。本 UA 已移除 wv，
 * 与真实 Chrome 移动浏览器 + 米游社 H5 内嵌形态完全一致。
 */
private const val CAPTCHA_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-S9180 Build/TP1A.220624.014) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 " +
        "Mobile Safari/537.36 miHoYoBBS/2.75.1"

/**
 * 验证 WebView 专用的 OkHttp 客户端（shouldInterceptRequest 里代发明文重写请求）。
 * 懒加载单例：整个 App 生命周期只建一次。
 */
private val captchaClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(15))
        .followRedirects(true)
        .build()
}

private fun captchaHttpClient(): okhttp3.OkHttpClient = captchaClient

/**
 * geetest 滑块验证页。
 * gt.js 为 geetest 官方组件（static.geetest.com），embed 模式直接内嵌展示。
 *
 * ── 环境伪装脚本（2026-09 定罪修复）──────────────────────────
 * Android WebView 缺失真实 Chrome 的多个特征对象，极验环境检测
 * 命中后静默降级为"本地假验证"（validate 为前端伪造，极验二次
 * 校验返回 seccode:false）。伪装清单：
 *   - window.chrome（WebView 无此对象，Chrome 有）
 *   - navigator.plugins（WebView 为空数组）
 *   - navigator.languages
 * 该脚本必须最先于所有极验脚本执行。
 */
private const val CAPTCHA_HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
html,body{margin:0;padding:0;background:#fff;font-family:sans-serif;height:100%;overflow-x:hidden}
#msg{color:#999;font-size:14px;text-align:center;padding:24px 12px;line-height:1.6}
#captcha{width:100%}
/* 让 geetest 组件宽度跟随屏幕，避免图片被压缩 */
.geetest_holder,.geetest_box,.geetest_wind{width:100%!important}
.geetest_wind .geetest_widget{width:100%!important}
</style>
<script>
/* ==== 浏览器环境伪装（必须先于极验脚本执行） ==== */
(function () {
  try {
    if (!window.chrome) {
      window.chrome = {
        runtime: {},
        loadTimes: function () { return {}; },
        csi: function () { return {}; },
        app: { isInstalled: false }
      };
    }
  } catch (e) { }
  try {
    if (!navigator.plugins || navigator.plugins.length === 0) {
      Object.defineProperty(navigator, 'plugins', {
        get: function () {
          return [
            { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
            { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
            { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
          ];
        },
        configurable: true
      });
    }
  } catch (e) { }
  try {
    Object.defineProperty(navigator, 'languages', {
      get: function () { return ['zh-CN', 'zh', 'en-US', 'en']; },
      configurable: true
    });
  } catch (e) { }
  try {
    if (!window.Notification) {
      window.Notification = { permission: 'denied', requestPermission: function (cb) { cb('denied'); } };
    }
  } catch (e) { }
})();
</script>
</head>
<body>
<div id="msg">正在加载验证组件…</div>
<div id="captcha"></div>
<script src="https://static.geetest.com/static/js/gt.0.4.9.js"></script>
<script>
function startGeetestJson(payloadJson) {
  var p = JSON.parse(payloadJson);
  startGeetest(p.gt, p.challenge, p.newCaptcha);
}

function startGeetest(gt, challenge, newCaptcha) {
  var msg = document.getElementById('msg');
  function reportError(m) {
    if (window.AndroidBridge && window.AndroidBridge.onCaptchaError) {
      window.AndroidBridge.onCaptchaError(String(m));
    }
  }
  if (typeof initGeetest !== 'function') {
    msg.textContent = '验证组件加载失败，请检查网络后重试';
    reportError('gt.js 未加载（static.geetest.com 不可达）');
    return;
  }
  initGeetest({
    gt: gt,
    challenge: challenge,
    offline: false,
    new_captcha: newCaptcha === true,
    product: 'embed',
    width: '100%',
    lang: 'zh-cn'
  }, function (captchaObj) {
    msg.style.display = 'none';
    captchaObj.appendTo('#captcha');

    // ── 降级检测（2026-09 强化）────────────────────────────
    // 旧探测只查 .geetest_holder 的 className，对 fullpage 9.x 的
    // DOM 结构无效（用户日志实锤：降级发生但探测未触发）。
    // 新探测三板斧：
    //  1. className 全容器扫描（fallback/offline 类名）
    //  2. captchaObj 实例的 offline/fallback 标志位
    //  3. getValidate() 结果的形态自检（假 validate 常为 md5(challenge)）
    function probeDegraded(tag) {
      try {
        var cls = (document.querySelector('.geetest_holder') || document.querySelector('.geetest_box') || { className: '' }).className || '';
        var objOffline = (captchaObj && (captchaObj.offline === true || captchaObj.fallback === true));
        if (/fallback|offline/i.test(cls) || objOffline) {
          if (window.AndroidBridge && window.AndroidBridge.onCaptchaDegraded) {
            window.AndroidBridge.onCaptchaDegraded('probe@' + tag + ' cls=' + cls + ' objOffline=' + !!objOffline);
          }
          return true;
        }
      } catch (e) { }
      return false;
    }
    setTimeout(function () { probeDegraded('800ms'); }, 800);
    setTimeout(function () { probeDegraded('3s'); }, 3000);

    captchaObj.onSuccess(function () {
      var v = captchaObj.getValidate();
      // 成功回调时的真伪自检：真 validate 与 challenge 无哈希关系，
      // failback 假 validate 常等于 md5(challenge)。形态信息通过独立
      // 回调上报（不污染 validateJson 本体），由原生侧结合极验预检裁决。
      var probe = 'unknown';
      try {
        if (v && v.geetest_challenge && v.geetest_validate) {
          if (v.geetest_validate === v.geetest_challenge) {
            probe = 'validate==challenge(SUSPECT)';
          } else if (String(v.geetest_validate).length !== 32) {
            probe = 'validate_len=' + String(v.geetest_validate).length + '(SUSPECT)';
          } else {
            probe = 'validate_len=32';
          }
        } else if (v && v.lot_number) {
          probe = 'v4';
        }
      } catch (e) {
        probe = 'ex:' + e.message;
      }
      probeDegraded('onSuccess');
      if (window.AndroidBridge) {
        try {
          if (window.AndroidBridge.onProbeInfo) {
            window.AndroidBridge.onProbeInfo(probe);
          }
        } catch (e) { }
        if (v) {
          // 整体回传原始对象，由原生侧识别 v3/v4 字段
          window.AndroidBridge.onValidated(JSON.stringify(v));
        } else {
          // getValidate() 为空：说明组件未产出结果，回报空对象便于诊断
          window.AndroidBridge.onValidated('{"__empty__":true}');
        }
      }
    });
    captchaObj.onError(function () {
      msg.style.display = 'block';
      msg.textContent = '验证组件出错，请关闭后重试';
    });
  });
}
</script>
</body>
</html>"""

/** 单项资源：数值 + 进度条 + 回满倒计时 */
@Composable
private fun DailyNoteStat(
    label: String,
    current: Int,
    max: Int,
    recoverySeconds: Long,
    fetchedAt: Long,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val ratio = if (max > 0) (current.toFloat() / max).coerceIn(0f, 1f) else 0f
    val full = current >= max
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = homeTextMid()
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$current",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = accent
            )
            Text(
                text = "/$max",
                style = MaterialTheme.typography.labelSmall,
                color = homeTextLow()
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth(),
            color = accent,
            trackColor = homeTrack()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (full) "已回满"
            else "回满还需 ${recoveryCountdown(recoverySeconds, fetchedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = homeTextLow()
        )
    }
}

/**
 * 本地推算恢复倒计时，不额外请求服务器。
 *
 * 接口返回的是「拉取时刻」的剩余秒数，配合 [fetchedAt] 本地递减，
 * 每秒刷新一次文案；归零后停在 00:00，等待下次拉取校正。
 */
@Composable
private fun recoveryCountdown(totalSeconds: Long, fetchedAt: Long): String {
    var now by remember(fetchedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(fetchedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = ((now - fetchedAt) / 1000).coerceAtLeast(0L)
    val remain = (totalSeconds - elapsed).coerceAtLeast(0L)
    val hours = remain / 3600
    val minutes = remain % 3600 / 60
    val seconds = remain % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

// ============================ Hero + 运气环 ============================

@Composable
private fun HeroLuckCard(uiState: HomeUiState) {
    // 角色池301和400各自显示单池统计，汇总时两个池都计入
    val stats = listOfNotNull(
        uiState.characterStats,
        uiState.character2Stats,
        uiState.weaponStats,
        uiState.standardStats,
        uiState.noviceStats,
        uiState.chronicledStats
    )
    val totalPulls = stats.sumOf { it.totalPulls }
    val totalFiveStars = stats.sumOf { it.fiveStarCount }
    // 使用计算引擎生成的全局报告平均值，禁止在 UI 层用 totalPulls / totalFiveStars
    val avgPulls = uiState.report?.avgPullsPerFiveStar ?: 0.0

    // UP 率：仅角色池（301 + 400）统计
    val upFiveStars = (uiState.characterStats?.upFiveStarCount ?: 0) +
            (uiState.character2Stats?.upFiveStarCount ?: 0)
    val charFiveStars = (uiState.characterStats?.fiveStarCount ?: 0) +
            (uiState.character2Stats?.fiveStarCount ?: 0)
    val upRate = if (charFiveStars > 0) upFiveStars.toDouble() / charFiveStars * 100 else 0.0

    // 运气评分：使用计算引擎基于真实概率模型生成的综合运气分
    val luckScore = uiState.report?.overallLuckScore ?: 0
    val luckConfidence = uiState.report?.overallLuckConfidence ?: LuckConfidence.INSUFFICIENT
    val luckVerdict = luckVerdictText(luckScore)

    val gold = wishAccentGold()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .goldGlowBorder(
                glowColor = gold,
                radius = 28.dp,
                shape = WishShapes.lg
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        shape = WishShapes.lg,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .homeHeroCardGradient()
                .drawBehind {
                    // 顶部鎏金氛围光（模仿网页版 hero 右上角 radial-gradient）
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFFE7C877).copy(alpha = 0.13f), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(size.width * 0.92f, size.height * 0.06f),
                            radius = size.width * 0.85f
                        )
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 账号信息行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.isLoggedIn) (uiState.nickname ?: "旅行者")
                        else "本地数据",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = homeTextHigh()
                    )
                    Text(
                        text = "UID: ${uiState.uid ?: "未绑定"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = homeTextMid()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 大数字
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$totalPulls",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = gold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "抽",
                        style = MaterialTheme.typography.titleMedium,
                        color = homeTextMid(),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = "五星 $totalFiveStars · 平均 ${String.format("%.1f", avgPulls)} 抽出金",
                    style = MaterialTheme.typography.bodySmall,
                    color = homeTextMid()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 三栏统计
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    HeroStat(
                        label = "五星数",
                        value = "$totalFiveStars",
                        valueColor = FiveStarColor,
                        modifier = Modifier.weight(1f)
                    )
                    HeroStat(
                        label = "平均出金",
                        value = if (totalFiveStars > 0)
                            String.format("%.1f", avgPulls) else "—",
                        valueSuffix = if (totalFiveStars > 0) "抽" else "",
                        modifier = Modifier.weight(1f)
                    )
                    HeroStat(
                        label = "UP率",
                        value = if (charFiveStars > 0)
                            String.format("%.0f", upRate) else "—",
                        valueSuffix = if (charFiveStars > 0) "%" else "",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 运气指数：整行轻量条，彻底避开右侧窄栏的挤压与截断
                if (totalFiveStars > 0) {
                    LuckScoreStrip(
                        score = luckScore,
                        confidenceName = luckConfidence.displayName,
                        verdict = luckVerdict
                    )
                } else {
                    // 空态：没有五星时不渲染 0 分，避免误导为"0 分非酋本酋"
                    Text(
                        text = "暂无运气分析 · 出金后生成",
                        style = MaterialTheme.typography.labelSmall,
                        color = homeTextLow()
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    valueSuffix: String = ""
) {
    val effectiveValueColor = valueColor ?: homeTextHigh()
    Column(
        modifier = modifier
            .clip(WishShapes.xs)
            .background(homeChip())
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = homeTextMid()
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = effectiveValueColor
            )
            if (valueSuffix.isNotEmpty()) {
                Text(
                    text = valueSuffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = effectiveValueColor,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

/** 运气指数轻量条：整行「标签 + 细进度条 + 分数」，下方一行结论，不再占据侧栏 */
@Composable
private fun LuckScoreStrip(
    score: Int,
    confidenceName: String,
    verdict: String,
    modifier: Modifier = Modifier
) {
    val animatedScore = remember { Animatable(0f) }
    LaunchedEffect(score) {
        animatedScore.snapTo(0f)
        animatedScore.animateTo(score.toFloat(), tween(1200, easing = FastOutSlowInEasing))
    }

    val gold = wishAccentGold()
    val fraction = (animatedScore.value / 100f).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "运气指数",
                style = MaterialTheme.typography.labelSmall,
                color = homeTextMid()
            )
            Spacer(modifier = Modifier.width(10.dp))
            // 细进度条：轨道 + 金色渐变填充，长度随分值生长；无扫光、无往复动画
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(homeTrack())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(gold.copy(alpha = 0.55f), gold)
                            )
                        )
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${animatedScore.value.toInt()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = gold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$verdict · $confidenceName",
            style = MaterialTheme.typography.labelSmall,
            color = homeTextLow()
        )
    }
}



// ============================ 保底进度网格 ============================

@Composable
private fun PityGridCard(
    label: String,
    poolStats: PoolStats?,
    modifier: Modifier = Modifier
) {
    if (poolStats == null) {
        GlassSurface(
            modifier = modifier,
            shape = WishShapes.md
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = homeTextLow()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = homeTextWeak()
                )
            }
        }
        return
    }

    val isNovice = poolStats.poolType == GachaType.NOVICE.value
    // 新手池：pityCeiling=20 是「池总抽数」，不是五星保底阈值，进度语义为"已抽 X/20"
    // 其他池：进度语义"已垫抽 X/保底上限"
    val pityPercent = (poolStats.currentPity.toFloat() / poolStats.pityCeiling).coerceIn(0f, 1f)
    val pityLeft = poolStats.pityCeiling - poolStats.currentPity
    val progressColor = if (isNovice) {
        noviceProgressColor(poolStats.currentPity, poolStats.pityCeiling)
    } else {
        pityProgressColor(poolStats.currentPity, poolStats.pityCeiling)
    }

    GlassSurface(
        modifier = modifier,
        shape = WishShapes.md
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = homeTextMid()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "${poolStats.currentPity}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = progressColor
                )
                Text(
                    text = "/${poolStats.pityCeiling}",
                    style = MaterialTheme.typography.labelSmall,
                    color = homeTextLow()
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // 胶囊形进度条：使用 BoxWithConstraints 显式指定宽度，避免 Surface 内部
            // 约束传播导致 fillMaxWidth(fraction) 失效（进度条永远显示 100%）
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(WishShapes.pill)
                    .background(homeTrack())
            ) {
                Box(
                    modifier = Modifier
                        .width(maxWidth * pityPercent)
                        .height(6.dp)
                        .clip(WishShapes.pill)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    progressColor.copy(alpha = 0.55f),
                                    progressColor,
                                    progressColor.copy(alpha = 0.9f)
                                )
                            )
                        )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isNovice) {
                    // 新手池只有 20 抽：抽满自动关闭，没有五星保底概念
                    if (pityLeft <= 0) "新手池已关闭" else "池剩余 ${pityLeft} 抽（共 ${poolStats.pityCeiling} 抽）"
                } else {
                    "距保底 ${pityLeft} 抽"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isNovice) {
                    when {
                        pityLeft <= 0 -> homeTextHigh()
                        poolStats.currentPity >= 15 -> if (isWishDark()) Color(0xFF8AB4FF) else Color(0xFF1E4FD8)
                        else -> homeTextHigh()
                    }
                } else {
                    if (poolStats.currentPity >= 60) progressColor
                    else homeTextHigh()
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 本池累计统计（用户在首页即可看到各池的"总抽数 / 五星数"，不用切到统计页）
            val fiveStarColor = FiveStarColor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "总 ${poolStats.totalPulls} 抽",
                    style = MaterialTheme.typography.labelSmall,
                    color = homeTextMid()
                )
                Text(
                    text = "五星 ${poolStats.fiveStarCount}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = fiveStarColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun pityProgressColor(currentPity: Int, ceiling: Int): Color {
    val remaining = ceiling - currentPity
    return when {
        remaining <= 10 -> wishAccentGold()
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * 新手池进度颜色：远距离用星蓝，接近用完用金色。
 */
@Composable
private fun noviceProgressColor(currentPity: Int, ceiling: Int): Color {
    val remaining = if (ceiling <= 0) 0 else ceiling - currentPity
    return when {
        remaining <= 4 -> wishAccentGold()
        else -> MaterialTheme.colorScheme.primary
    }
}

// ============================ 最近五星横滑 ============================

@Composable
private fun RecentFiveStarsRow(
    records: List<GachaRecordEntity>,
    intervals: List<Int>
) {
    val pairs = records.zip(intervals)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(pairs) { index, (record, interval) ->
            RecentFiveStarCard(
                record = record,
                interval = interval,
                playGlow = index == 0
            )
        }
    }
}

@Composable
private fun RecentFiveStarCard(
    record: GachaRecordEntity,
    interval: Int,
    playGlow: Boolean = false
) {
    val glow = rememberFiveStarGlow(play = playGlow)
    GlassSurface(
        modifier = Modifier
            .width(130.dp)
            .drawBehind {
                if (glow > 0f) {
                    drawRoundRect(
                        color = FiveStarColor.copy(alpha = glow),
                        cornerRadius = CornerRadius(18.dp.toPx())
                    )
                }
            },
        shape = WishShapes.md,
        borderStroke = BorderStroke(1.5.dp, FiveStarColor.copy(alpha = 0.4f))
    ) {
        Box {
            Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 28.dp, bottom = 12.dp)) {
                Text(
                    text = record.itemName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = homeTextHigh(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "距上次 $interval 抽",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = FiveStarColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.time.substringBefore(" ").ifEmpty { record.time },
                    style = MaterialTheme.typography.labelSmall,
                    color = homeTextLow()
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                shape = WishShapes.xs,
                color = FiveStarColor.copy(alpha = 0.22f)
            ) {
                Text(
                    text = "5",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = FiveStarColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ============================ 运气拆解 ============================

@Composable
private fun LuckDetailCard(uiState: HomeUiState) {
    // 角色池301和400各自显示单池统计，汇总时两个池都计入
    val stats = listOfNotNull(
        uiState.characterStats,
        uiState.character2Stats,
        uiState.weaponStats,
        uiState.standardStats,
        uiState.noviceStats,
        uiState.chronicledStats
    )
    val totalPulls = stats.sumOf { it.totalPulls }
    val totalFiveStars = stats.sumOf { it.fiveStarCount }
    // 使用计算引擎生成的全局报告数据，禁止在 UI 层自行计算
    val avgPulls = uiState.report?.avgPullsPerFiveStar ?: 0.0
    val worstLuck = uiState.report?.worstLuck ?: 0
    val bestLuck = uiState.report?.bestLuck ?: 0
    val recentInterval = uiState.recentFiveStarIntervals.firstOrNull() ?: 0

    // UP率：仅角色池（301 + 400）统计
    val upFiveStars = (uiState.characterStats?.upFiveStarCount ?: 0) +
            (uiState.character2Stats?.upFiveStarCount ?: 0)
    val charFiveStars = (uiState.characterStats?.fiveStarCount ?: 0) +
            (uiState.character2Stats?.fiveStarCount ?: 0)
    val upRate = if (charFiveStars > 0) upFiveStars.toDouble() / charFiveStars * 100 else 0.0

    val success = wishSuccess()
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = WishShapes.md
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            LuckDetailRow("平均出金", if (totalFiveStars > 0) String.format("%.1f 抽", avgPulls) else "—", wishAccentGold())
            LuckDetailRow("最近五星", if (recentInterval > 0) "$recentInterval 抽" else "—", success)
            LuckDetailRow("最非", if (worstLuck > 0) "$worstLuck 抽" else "—", wishWarning())
            LuckDetailRow("最欧", if (bestLuck > 0) "$bestLuck 抽" else "—", success)
            LuckDetailRow("UP成功率", String.format("%.0f%%", upRate), MaterialTheme.colorScheme.primary)
            LuckDetailRow("总抽数", "$totalPulls", homeTextHigh())
        }
    }
}

@Composable
private fun LuckDetailRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = homeTextLow()
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

// ============================ 同步 / 登录 ============================

@Composable
private fun SyncSection(
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    navController: NavController
) {
    if (uiState.isLoggedIn) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (val sync = uiState.syncState) {
                is SyncState.Idle, is SyncState.Success -> {
                    if (sync is SyncState.Success) {
                        Text(
                            text = "上次同步：新增 ${sync.totalNew} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Button(
                        onClick = { viewModel.sync() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = WishShapes.lg,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE7C877),
                            contentColor = Color(0xFF1A1508)
                        )
                    ) {
                        Text("同步抽卡记录", fontWeight = FontWeight.Bold)
                    }
                }
                is SyncState.Loading -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = sync.message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is SyncState.Progress -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "正在同步：${sync.currentPool}（${sync.totalRecords} 条）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "新增 ${sync.newRecords} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is SyncState.Error -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = sync.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedButton(
                            onClick = { viewModel.sync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重试")
                        }
                    }
                }
                is SyncState.RateLimited -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "访问过于频繁，请 ${sync.cooldownSeconds} 秒后重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedButton(
                            onClick = { viewModel.sync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("我知道了")
                        }
                    }
                }
            }
        }
    } else {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = WishShapes.md
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "本地数据",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = homeTextHigh()
                    )
                    Text(
                        text = "UID: ${uiState.uid ?: "未绑定"}（手动导入）",
                        style = MaterialTheme.typography.bodySmall,
                        color = homeTextLow()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { navController.navigate(Screen.Auth.route) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = WishShapes.lg,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE7C877),
                            contentColor = Color(0xFF1A1508)
                        )
                    ) {
                    Text("登录米游社以同步最新数据", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ============================ 小工具 ============================

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(WishShapes.pill)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            wishAccentGold().copy(alpha = 0.95f),
                            wishAccentGold().copy(alpha = 0.45f)
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================ 运气评语 ============================

@Composable
private fun rememberFiveStarGlow(play: Boolean): Float {
    val reduceMotion = rememberReduceMotion()
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(play, reduceMotion) {
        if (play && !reduceMotion) {
            alpha.snapTo(0f)
            alpha.animateTo(0.25f, tween(200))
            alpha.animateTo(0f, tween(400))
        }
    }
    return alpha.value
}

/**
 * 运气分文案。
 *
 * 语义：运气分 = P(理论概率模型下出金抽数 > 你的抽数) × 100，
 * 即"模型下比你更非的比例"——对比对象是官方概率生成的理想分布，
 * 不是全服/真实玩家数据库。文案因此使用"模型预期"措辞，
 * 避免用户误读为"与全服玩家比"。
 */
private fun luckVerdictText(score: Int): String = when {
    score >= 90 -> "远超模型预期"
    score >= 70 -> "优于模型预期"
    score >= 45 -> "与模型预期相近"
    score >= 25 -> "低于模型预期"
    else -> "远低于模型预期"
}


// ============================ 首页卡片双主题 helpers ============================
// 深色模式：卡片为深色夜空玻璃，文字白系鎏金；
// 浅色模式：卡片为白/近白，文字自动切到深蓝黑 —— 杜绝"白字浮白卡"与整片深蓝大卡。
@Composable
private fun homeTextHigh(): Color =
    if (isWishDark()) Color.White else Color(0xFF17203A)

@Composable
private fun homeTextMid(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.62f) else Color(0xFF17203A).copy(alpha = 0.68f)

@Composable
private fun homeTextLow(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.45f) else Color(0xFF17203A).copy(alpha = 0.55f)

@Composable
private fun homeTextWeak(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.30f) else Color(0xFF17203A).copy(alpha = 0.36f)

@Composable
private fun homeTrack(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.14f) else Color(0xFF17203A).copy(alpha = 0.12f)

@Composable
private fun homeChip(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.07f) else Color(0xFF17203A).copy(alpha = 0.05f)

@Composable
private fun homeCardBorder(): Color =
    if (isWishDark()) Color.White.copy(alpha = 0.08f) else Color(0xFF17203A).copy(alpha = 0.10f)

// ==================== 日志导出（已迁出） ====================
// 2026-09-20：ExportLogButton（首页右上角浮动按钮）与 LogExportDialog 弹窗
// 已整体迁至 ui/logexport/LogExport.kt 共享组件 +「设置 → 关于」区块入口。
