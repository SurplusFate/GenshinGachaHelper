package com.genshin.gachahelper.ui.auth

import android.graphics.Bitmap
import com.genshin.gachahelper.auth.GameRole

data class AuthUiState(
    val phase: AuthPhase = AuthPhase.LOADING,
    val loginMethod: LoginMethod = LoginMethod.WEBVIEW,
    val qrBitmap: Bitmap? = null,
    val gameRoles: List<GameRole> = emptyList(),
    val selectedRole: GameRole? = null,
    val authKey: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val statusText: String = "",
    val debugInfo: String? = null
)

enum class LoginMethod {
    QR_CODE,    // 扫码登录
    WEBVIEW     // 验证码/密码登录（WebView）
}

enum class AuthPhase {
    LOADING,           // 正在加载
    QR_DISPLAY,        // 显示二维码，等待扫描
    QR_SCANNED,        // 已扫描，等待确认
    WEBVIEW_LOGIN,     // WebView 登录页面
    EXCHANGING_TOKEN,  // 正在换取 stoken
    FETCHING_ROLES,    // 正在获取角色
    ROLE_SELECT,       // 选择角色
    GENERATING_KEY,    // 正在生成 authkey
    DONE               // 完成
}
