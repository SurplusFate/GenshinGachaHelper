package com.genshin.gachahelper.ui.auth

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView as NativeWebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.auth.GameRole
import com.genshin.gachahelper.ui.theme.WishShapes
import com.genshin.gachahelper.ui.theme.wishSkyBackground
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState

@Composable
fun AuthScreen(
    navController: NavController,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().wishSkyBackground()) {
        // 登录方式切换（仅在可交互的登录/等待阶段显示；换取、选角色、完成阶段隐藏，
        // 避免用户中途切换打断正在进行的登录流程）
        val showMethodSwitcher = uiState.phase == AuthPhase.LOADING ||
            uiState.phase == AuthPhase.QR_DISPLAY ||
            uiState.phase == AuthPhase.QR_SCANNED ||
            uiState.phase == AuthPhase.WEBVIEW_LOGIN
        if (showMethodSwitcher) {
            LoginMethodSwitcher(
                selected = uiState.loginMethod,
                onSelect = { method ->
                    when (method) {
                        LoginMethod.QR_CODE -> viewModel.switchToQrCode()
                        LoginMethod.WEBVIEW -> viewModel.switchToWebView()
                    }
                }
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when (uiState.phase) {
                AuthPhase.LOADING -> LoadingView(uiState.statusText)
                AuthPhase.QR_DISPLAY -> QrCodeView(
                    bitmap = uiState.qrBitmap,
                    statusText = uiState.statusText,
                    error = uiState.error,
                    debugInfo = uiState.debugInfo,
                    onRefresh = { viewModel.refreshQrCode() }
                )
                AuthPhase.QR_SCANNED -> ScannedView(
                    statusText = uiState.statusText,
                    debugInfo = uiState.debugInfo
                )
                AuthPhase.WEBVIEW_LOGIN -> WebViewLoginView(
                    error = uiState.error,
                    debugInfo = uiState.debugInfo,
                    onLoginComplete = { viewModel.onWebViewLoginComplete() }
                )
                AuthPhase.EXCHANGING_TOKEN -> LoadingView(uiState.statusText)
                AuthPhase.FETCHING_ROLES -> LoadingView(uiState.statusText)
                AuthPhase.ROLE_SELECT -> RoleSelectView(
                    roles = uiState.gameRoles,
                    error = uiState.error,
                    debugInfo = uiState.debugInfo,
                    onSelect = { viewModel.selectRole(it) }
                )
                AuthPhase.GENERATING_KEY -> LoadingView(uiState.statusText)
                AuthPhase.DONE -> DoneView(
                    role = uiState.selectedRole,
                    onConfirm = { navController.navigateUp() }
                )
            }
        }
    }
}

@Composable
private fun LoginMethodSwitcher(
    selected: LoginMethod,
    onSelect: (LoginMethod) -> Unit
) {
    TabRow(selectedTabIndex = if (selected == LoginMethod.WEBVIEW) 1 else 0) {
        Tab(
            selected = selected == LoginMethod.QR_CODE,
            onClick = { onSelect(LoginMethod.QR_CODE) },
            text = { Text("扫码登录", style = MaterialTheme.typography.bodyMedium) }
        )
        Tab(
            selected = selected == LoginMethod.WEBVIEW,
            onClick = { onSelect(LoginMethod.WEBVIEW) },
            text = { Text("账号密码/验证码", style = MaterialTheme.typography.bodyMedium) }
        )
    }
}

@Composable
fun WebViewLoginView(
    error: String?,
    debugInfo: String?,
    onLoginComplete: () -> Unit
) {
    val webViewState = rememberWebViewState(url = "https://user.mihoyo.com/#/login")

    // 始终持有最新的回调引用，避免 remember 出的 client 捕获到过期的 lambda
    val currentOnLoginComplete by rememberUpdatedState(onLoginComplete)

    // 自动检测登录完成的 WebViewClient：当 URL 离开 #/login 且仍位于 user.mihoyo.com
    // （含子路径）时认为登录已成功，自动触发凭证读取流程，无需用户手动点击按钮。
    val loginClient = remember {
        object : AccompanistWebViewClient() {
            private var triggered = false

            override fun shouldOverrideUrlLoading(
                view: NativeWebView,
                request: WebResourceRequest
            ): Boolean {
                checkLoginSuccess(request.url?.toString())
                return false
            }

            override fun onPageStarted(view: NativeWebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                checkLoginSuccess(url)
            }

            override fun doUpdateVisitedHistory(
                view: NativeWebView,
                url: String?,
                isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                checkLoginSuccess(url)
            }

            private fun checkLoginSuccess(url: String?) {
                if (triggered || url.isNullOrBlank()) return
                // 登录页地址：https://user.mihoyo.com/#/login
                // 登录成功后 URL 会脱离 #/login 并停留在 user.mihoyo.com（含子路径）
                val onUserMihoyo = url.contains("user.mihoyo.com")
                val leftLoginPage = !url.contains("#/login")
                if (onUserMihoyo && leftLoginPage) {
                    triggered = true
                    currentOnLoginComplete()
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // WebView 占主要空间
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            WebView(
                state = webViewState,
                modifier = Modifier.fillMaxSize(),
                client = loginClient,
                onCreated = { webView ->
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }
                    // 启用 cookie
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webView, true)
                    }
                }
            )

            // 加载中的转圈
            if (webViewState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        // 底部操作区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = onLoginComplete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("我已登录，下一步")
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "请在上方页面中完成登录，然后点击此按钮",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // 调试信息
            if (debugInfo != null && debugInfo.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "调试信息",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = debugInfo.take(1500),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QrCodeView(
    bitmap: Bitmap?,
    statusText: String,
    error: String?,
    debugInfo: String?,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        if (bitmap != null) {
            Card(
                modifier = Modifier.size(260.dp),
                shape = WishShapes.lg,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "请使用米游社 App 扫一扫登录，或在分屏模式下操作：长按底部任务键开启分屏，在另一半屏幕打开米游社 → 我的 → 扫一扫",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onRefresh) {
                Text("刷新二维码")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "提示：截图扫码可能无法识别，请使用米游社 App 或分屏操作",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRefresh) {
                    Text("重新获取二维码")
                }
            } else {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 调试信息
        if (debugInfo != null && debugInfo.isNotBlank()) {
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "调试信息",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = debugInfo.take(1500),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ScannedView(
    statusText: String,
    debugInfo: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusText.ifBlank { "已扫描，请在米游社中确认登录" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 调试信息
        if (debugInfo != null && debugInfo.isNotBlank()) {
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "调试信息",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = debugInfo.take(1500),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun LoadingView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun RoleSelectView(
    roles: List<GameRole>,
    error: String?,
    debugInfo: String?,
    onSelect: (GameRole) -> Unit
) {
    var selectedUid by remember { mutableStateOf(roles.firstOrNull()?.uid ?: "") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "选择要同步的原神角色",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(roles) { role ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedUid = role.uid },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedUid == role.uid)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedUid == role.uid,
                            onClick = { selectedUid = role.uid }
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                        Column {
                            Text(
                                text = role.nickname,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "UID: ${role.uid}  等级: ${role.level}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (error != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            if (debugInfo != null && debugInfo.isNotBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "调试信息",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = debugInfo.take(1000),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                roles.find { it.uid == selectedUid }?.let { onSelect(it) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("确认选择")
        }
    }
}

@Composable
fun DoneView(
    role: GameRole?,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "授权成功",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        role?.let {
            Text(
                text = it.nickname,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "UID: ${it.uid}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "可以开始同步你的抽卡记录了",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.padding(horizontal = 48.dp)
        ) {
            Text("完成")
        }
    }
}
