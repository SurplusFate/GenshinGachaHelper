package com.genshin.gachahelper.ui.auth

import android.graphics.Bitmap
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.auth.ApiResult
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.auth.GameRole
import com.genshin.gachahelper.auth.MihoyoApiService
import com.genshin.gachahelper.auth.QrCodeData
import com.genshin.gachahelper.auth.QrCodeGenerator
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val mihoyoApi: MihoyoApiService,
    private val sessionEventBus: SessionEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var qrData: QrCodeData? = null
    private var pollJob: Job? = null
    private var pollCount = 0

    init {
        switchToQrCode()
    }

    fun switchToQrCode() {
        pollJob?.cancel()
        setState {
            copy(
                loginMethod = LoginMethod.QR_CODE,
                phase = AuthPhase.LOADING,
                error = null,
                statusText = "正在获取二维码...",
                qrBitmap = null,
                debugInfo = null
            )
        }
        fetchQrCode()
    }

    fun switchToWebView() {
        pollJob?.cancel()
        setState {
            copy(
                loginMethod = LoginMethod.WEBVIEW,
                phase = AuthPhase.WEBVIEW_LOGIN,
                error = null,
                statusText = "",
                qrBitmap = null,
                debugInfo = "请在下方页面中完成登录\n登录完成后点击「我已登录」按钮"
            )
        }
    }

    // ------------------------------------------------------------------
    // 扫码登录
    // ------------------------------------------------------------------

    private fun fetchQrCode() {
        pollJob?.cancel()
        pollCount = 0
        setState {
            copy(
                phase = AuthPhase.LOADING,
                error = null,
                statusText = "正在获取二维码...",
                qrBitmap = null,
                debugInfo = null
            )
        }

        viewModelScope.launch {
            // 使用新的通行证扫码登录 API
            when (val result = mihoyoApi.createPassportQr()) {
                is ApiResult.Success -> {
                    qrData = result.data
                    val bitmap = withContext(Dispatchers.Default) {
                        QrCodeGenerator.generate(result.data.url, 600)
                    }
                    setState {
                        copy(
                            phase = AuthPhase.QR_DISPLAY,
                            qrBitmap = bitmap,
                            statusText = "请使用米游社扫码登录",
                            error = null,
                            debugInfo = "ticket: ${result.data.ticket.take(16)}...\ndevice: ${result.data.device.take(16)}..."
                        )
                    }
                    startPolling()
                }
                is ApiResult.Error -> {
                    setState {
                        copy(
                            phase = AuthPhase.QR_DISPLAY,
                            error = "[${result.step}] 获取二维码失败: ${result.message}",
                            statusText = "",
                            debugInfo = result.rawResponse.takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var retryCount = 0
            while (isActive) {
                delay(2000)
                val data = qrData ?: break
                pollCount++

                // 使用新的通行证扫码登录 API
                when (val result = mihoyoApi.queryPassportQrStatus(data.ticket, data.device)) {
                    is ApiResult.Success -> {
                        retryCount = 0
                        val status = result.data.status
                        when (status) {
                            "Created" -> {
                                setState {
                                    copy(
                                        debugInfo = "轮询第 ${pollCount} 次\n状态: $status (等待扫码)\n\n${result.data.rawResponse.take(300)}"
                                    )
                                }
                            }
                            "Scanned" -> {
                                setState {
                                    copy(
                                        phase = AuthPhase.QR_SCANNED,
                                        statusText = "已扫描，请在米游社中确认登录",
                                        debugInfo = "轮询第 ${pollCount} 次\n状态: $status (已扫码，等待确认)\n\n${result.data.rawResponse.take(300)}"
                                    )
                                }
                            }
                            "Confirmed" -> {
                                pollJob?.cancel()
                                val stoken = result.data.stoken
                                val mid = result.data.mid
                                val aid = result.data.aid
                                if (!stoken.isNullOrBlank() && !aid.isNullOrBlank()) {
                                    // 新 API 直接返回 stoken，无需再换 token
                                    savePassportCredentialsAndFetchRoles(stoken, mid, aid)
                                } else {
                                    setState {
                                        copy(
                                            error = "扫码确认成功，但获取凭证失败\nstoken=${stoken?.take(10)}..., mid=$mid, aid=$aid",
                                            phase = AuthPhase.QR_DISPLAY,
                                            qrBitmap = null,
                                            debugInfo = result.data.rawResponse
                                        )
                                    }
                                }
                            }
                            else -> {
                                setState {
                                    copy(
                                        debugInfo = "轮询第 ${pollCount} 次\n未知状态: $status\n\n${result.data.rawResponse}"
                                    )
                                }
                            }
                        }
                    }
                    is ApiResult.Error -> {
                        if (result.code == -106) {
                            pollJob?.cancel()
                            setState {
                                copy(
                                    phase = AuthPhase.QR_DISPLAY,
                                    qrBitmap = null,
                                    statusText = "二维码已过期",
                                    error = "二维码已过期，请点击刷新"
                                )
                            }
                        } else {
                            retryCount++
                            if (retryCount > 3) {
                                pollJob?.cancel()
                                setState {
                                    copy(
                                        error = "[${result.step}] 轮询失败: ${result.message}",
                                        debugInfo = result.rawResponse.takeIf { it.isNotBlank() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun savePassportCredentialsAndFetchRoles(stoken: String, mid: String?, aid: String) {
        setState {
            copy(
                phase = AuthPhase.EXCHANGING_TOKEN,
                statusText = "正在换取 cookie_token...",
                error = null,
                debugInfo = "扫码确认成功，正在换取 cookie_token...\nstoken: ${stoken.take(10)}...\naid: $aid\nmid: ${mid ?: "无"}"
            )
        }

        viewModelScope.launch {
            // 先保存 stoken（getCookieTokenByStoken 内部会用 device_id）
            authRepository.saveLoginCredentials(
                stoken = stoken,
                ltuid = aid,
                mid = mid
            )

            // 步骤1：用 stoken 换 cookie_token（必需，否则 getUserGameRolesByCookie 返回 -100）
            var cookieToken: String? = null
            when (val cookieResult = mihoyoApi.getCookieTokenByStoken(stoken, aid, mid)) {
                is ApiResult.Success -> {
                    cookieToken = cookieResult.data
                    setState {
                        copy(
                            debugInfo = "cookie_token 获取成功: ${cookieToken!!.take(16)}...\n正在换取 ltoken..."
                        )
                    }
                }
                is ApiResult.Error -> {
                    setState {
                        copy(
                            debugInfo = "cookie_token 获取失败: ${cookieResult.message}\n${cookieResult.rawResponse.take(300)}"
                        )
                    }
                    // cookie_token 失败也可以尝试继续（部分接口可能不需要）
                }
            }

            // 步骤2：用 stoken 换 ltoken（可选，genAuthKey 可能需要）
            var ltoken: String? = null
            when (val ltokenResult = mihoyoApi.getLTokenByStoken(stoken, aid, mid)) {
                is ApiResult.Success -> {
                    ltoken = ltokenResult.data
                    setState {
                        copy(
                            debugInfo = (uiState.value.debugInfo ?: "") + "\nltoken 获取成功: ${ltoken!!.take(16)}..."
                        )
                    }
                }
                is ApiResult.Error -> {
                    setState {
                        copy(
                            debugInfo = (uiState.value.debugInfo ?: "") + "\nltoken 获取失败: ${ltokenResult.message}"
                        )
                    }
                }
            }

            // 保存完整凭证
            authRepository.saveLoginCredentials(
                stoken = stoken,
                ltuid = aid,
                mid = mid,
                cookieToken = cookieToken,
                ltoken = ltoken
            )

            setState {
                copy(
                    statusText = "正在获取游戏角色...",
                    debugInfo = "凭证获取完成\nstoken: ${stoken.take(10)}...\ncookie_token: ${cookieToken?.take(10) ?: "无"}\nltoken: ${ltoken?.take(10) ?: "无"}\n正在获取角色列表..."
                )
            }

            fetchGameRoles()
        }
    }

    private fun exchangeTokenByGameToken(uid: String, gameToken: String) {
        setState {
            copy(
                phase = AuthPhase.EXCHANGING_TOKEN,
                statusText = "正在获取登录凭证...",
                error = null,
                debugInfo = "正在用 game_token 换取 stoken...\nuid: $uid"
            )
        }

        viewModelScope.launch {
            when (val result = mihoyoApi.getTokenByGameToken(uid, gameToken)) {
                is ApiResult.Success -> {
                    val tokenInfo = result.data
                    authRepository.saveLoginCredentials(
                        stoken = tokenInfo.stoken,
                        ltuid = uid,
                        mid = tokenInfo.mid
                    )
                    fetchGameRoles()
                }
                is ApiResult.Error -> {
                    setState {
                        copy(
                            phase = AuthPhase.QR_DISPLAY,
                            qrBitmap = null,
                            error = "[${result.step}] 获取凭证失败: ${result.message}",
                            statusText = "请刷新二维码重试",
                            debugInfo = result.rawResponse.takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // WebView 登录
    // ------------------------------------------------------------------

    fun onWebViewLoginComplete() {
        setState {
            copy(
                phase = AuthPhase.EXCHANGING_TOKEN,
                statusText = "正在读取登录凭证...",
                error = null,
                debugInfo = "正在从浏览器读取登录信息..."
            )
        }

        viewModelScope.launch {
            try {
                // 确保 Cookie 同步完成
                CookieManager.getInstance().flush()
                // 增加延时，确保 WebView 内所有 cookie（stoken_v2 / login_ticket 等）都已落盘
                delay(1500)

                val cookieManager = CookieManager.getInstance()
                // 从多个域名读取 cookie 并合并，避免因 CookieManager 域名匹配规则不同
                // 而漏掉 stoken_v2 / login_ticket 等关键凭证。
                val domains = listOf(
                    "https://user.mihoyo.com",
                    "https://.mihoyo.com",
                    "https://mihoyo.com",
                    "https://api-takumi.mihoyo.com"
                )
                val cookieMap = mutableMapOf<String, String>()
                val cookieStringBuilder = StringBuilder()
                for (domain in domains) {
                    val raw = cookieManager.getCookie(domain) ?: continue
                    if (raw.isBlank()) continue
                    cookieStringBuilder.append(raw).append("; ")
                    val domainMap = parseCookies(raw)
                    for ((key, value) in domainMap) {
                        // 合并策略：保留非空值，已存在非空值时不覆盖
                        if (value.isNotBlank() && cookieMap[key].isNullOrBlank()) {
                            cookieMap[key] = value
                        }
                    }
                }
                val cookies = cookieStringBuilder.toString()

                val loginTicket = cookieMap["login_ticket"]
                val stokenV2 = cookieMap["stoken_v2"] ?: cookieMap["stoken"]
                val ltokenV2 = cookieMap["ltoken_v2"] ?: cookieMap["ltoken"]
                val cookieTokenV2 = cookieMap["cookie_token_v2"] ?: cookieMap["cookie_token"]
                val ltuid = cookieMap["ltuid_v2"]
                    ?: cookieMap["ltuid"]
                    ?: cookieMap["account_id_v2"]
                    ?: cookieMap["account_id"]
                val mid = cookieMap["account_mid_v2"]
                    ?: cookieMap["ltmid_v2"]
                    ?: cookieMap["mid"]

                setState {
                    copy(debugInfo = "凭证检测结果:\n" +
                        "stoken: ${stokenV2?.take(20) ?: "无"}\n" +
                        "login_ticket: ${loginTicket?.take(20) ?: "无"}\n" +
                        "cookie_token: ${cookieTokenV2?.take(20) ?: "无"}\n" +
                        "ltoken: ${ltokenV2?.take(20) ?: "无"}\n" +
                        "ltuid: $ltuid\n" +
                        "mid: $mid\n" +
                        "所有 key: ${cookieMap.keys.joinToString(", ")}"
                    )
                }

                when {
                    // 方案1：直接有 stoken_v2 → 走与扫码 Confirmed 完全同构的兑换链路
                    // （savePassportCredentialsAndFetchRoles：存 stoken → 换 cookie_token → 换 ltoken
                    // → fetchGameRoles → selectRole → generateAuthKey）
                    !stokenV2.isNullOrBlank() && !ltuid.isNullOrBlank() -> {
                        savePassportCredentialsAndFetchRoles(stokenV2, mid, ltuid)
                    }
                    // 方案2：有 login_ticket + ltuid → 用 getMultiTokenByLoginTicket 换真正 stoken
                    // 拿到 stoken 后同样走扫码一致的 savePassportCredentialsAndFetchRoles，
                    // 保证 cookie_token、ltoken 兑换、后续接口都和扫码等价。
                    !loginTicket.isNullOrBlank() && !ltuid.isNullOrBlank() -> {
                        exchangeTokenByLoginTicket(loginTicket, ltuid, cookieTokenV2, mid)
                    }
                    // 方案3：验证码登录场景 —— cookie 中没有 stoken/login_ticket，
                    // 但有 cookie_token_v2 + ltoken_v2 + ltuid。
                    // 这种情况下可以直接用 cookie_token 调 getUserGameRolesByCookie 获取角色，
                    // 用 ltoken 调 genAuthKey 生成授权码，完全不需要 stoken。
                    // stoken 只是换取 cookie_token/ltoken 的中间凭证，不是最终 API 的必须项。
                    !ltuid.isNullOrBlank() &&
                        (!cookieTokenV2.isNullOrBlank() || !ltokenV2.isNullOrBlank()) -> {
                        loginWithCookieTokenDirectly(ltuid, mid, cookieTokenV2, ltokenV2)
                    }
                    // 不满足以上：要么 ltuid 完全为空（没登录），要么登录页没下发任何可用 token。
                    else -> {
                        setState {
                            copy(
                                phase = AuthPhase.WEBVIEW_LOGIN,
                                error = if (ltuid == null) {
                                    "未检测到登录凭证，请确认已完成登录"
                                } else {
                                    "检测到账号（ltuid=$ltuid）但未获取到有效登录凭证。\n" +
                                        "请尝试：\n1. 退出账号后重新登录\n2. 改用扫码登录（推荐）"
                                },
                                statusText = "",
                                debugInfo = "Cookie 内容：\n${cookies.take(800)}\n\n" +
                                    "可用 key: ${cookieMap.keys.joinToString(", ")}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                setState {
                    copy(
                        phase = AuthPhase.WEBVIEW_LOGIN,
                        error = "读取凭证异常: ${e.message}",
                        debugInfo = e.stackTraceToString().take(500)
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // WebView 登录 - 方案3：直接用 cookie_token + ltoken 登录（验证码登录场景）
    // ------------------------------------------------------------------

    private fun loginWithCookieTokenDirectly(
        ltuid: String,
        mid: String?,
        cookieToken: String?,
        ltoken: String?
    ) {
        setState {
            copy(
                phase = AuthPhase.EXCHANGING_TOKEN,
                statusText = "正在验证登录凭证...",
                error = null,
                debugInfo = buildString {
                    append("检测到 WebView 登录凭证（验证码登录模式）\n")
                    append("ltuid: $ltuid\n")
                    append("cookie_token: ${if (cookieToken.isNullOrBlank()) "无" else "有"}\n")
                    append("ltoken: ${if (ltoken.isNullOrBlank()) "无" else "有"}\n")
                    append("mid: ${mid ?: "无"}\n")
                    append("\n正在验证凭证有效性...")
                }
            )
        }

        viewModelScope.launch {
            // 先用 cookie_token 换 stoken（genAuthKey 需要 stoken 才能通过，
            // 否则后续同步会报 -100 登录状态失效）。失败不直接终止，
            // 只作为 debugInfo 提示，后续看 genAuthKey 是否成功。
            var swappedStoken: String? = null
            if (!cookieToken.isNullOrBlank()) {
                setState { copy(statusText = "正在换取 stoken...") }
                when (val r = mihoyoApi.getStokenByCookieToken(cookieToken, ltuid, mid)) {
                    is ApiResult.Success -> {
                        swappedStoken = r.data
                        setState {
                            copy(debugInfo = (uiState.value.debugInfo
                                ?: "") + "\nstoken 兑换成功（使用 cookie_token_v2）")
                        }
                    }
                    is ApiResult.Error -> {
                        setState {
                            copy(debugInfo = (uiState.value.debugInfo
                                ?: "") + "\nstoken 兑换失败：${r.message}" +
                                "\n提示：若后续 generateAuthKey 报 -100，请改用扫码登录")
                        }
                    }
                }
            }

            // 保存凭证：优先使用已兑换到的 stoken（没有则保持 null）。
            // saveWebViewCredentials 在 stoken 为 null 时不会覆盖已存的值，
            // 所以这里用 saveLoginCredentials 显式写入（含 stoken → 和扫码登录产出同构）。
            if (swappedStoken != null) {
                authRepository.saveLoginCredentials(
                    stoken = swappedStoken,
                    ltuid = ltuid,
                    mid = mid,
                    cookieToken = cookieToken,
                    ltoken = ltoken
                )
            } else {
                authRepository.saveWebViewCredentials(
                    ltuid = ltuid,
                    mid = mid,
                    cookieToken = cookieToken,
                    ltoken = ltoken
                )
            }

            // 直接尝试获取游戏角色来验证凭证是否有效
            // getGameRoles 内部会调用 buildCookieString() 组装 cookie
            setState { copy(statusText = "正在获取游戏角色...") }
            fetchGameRoles()
        }
    }

    private suspend fun exchangeTokenByLoginTicket(
        loginTicket: String,
        uid: String,
        cookieToken: String?,
        mid: String?
    ) {
        setState {
            copy(
                statusText = "正在换取登录凭证...",
                debugInfo = "正在用 login_ticket 换取 stoken...\nuid: $uid"
            )
        }

        when (val result = mihoyoApi.getMultiTokenByLoginTicket(loginTicket, uid)) {
            is ApiResult.Success -> {
                val tokenInfo = result.data
                // 拿到真正的 stoken 后走与扫码一致的链路：
                // savePassportCredentialsAndFetchRoles 会再换 cookie_token + ltoken
                // （cookie_token 是 getUserGameRolesByCookie 必需的，ltoken 是 generateAuthKey 的保险）
                // 这样扫码与 WebView 登录产出的最终凭证集完全同构，不会出现一种登录能跑
                // 另一种登录报 -100 的分化。
                val existingCookieToken = if (!cookieToken.isNullOrBlank()) cookieToken else null
                authRepository.saveLoginCredentials(
                    stoken = tokenInfo.stoken,
                    ltuid = uid,
                    mid = mid,
                    cookieToken = existingCookieToken,
                    ltoken = tokenInfo.ltoken.ifBlank { null }
                )
                // 复用 savePassportCredentialsAndFetchRoles 的后半段：换 cookie_token + ltoken
                // → fetchGameRoles → selectRole → generateAuthKey。为避免重复覆盖掉已保存的
                // cookie_token/ltoken（WebView 里可能本来就有），这里的实现是直接调
                // fetchGameRoles，而 savePassportCredentialsAndFetchRoles 的"先存 stoken 再
                // 换两 token"的逻辑通过上面 saveLoginCredentials + 下面补两步骤完成。
                // 注：为减少重复网络请求，如果 WebView cookie 里已带 cookie_token 且已有 ltoken，
                // 就不再调用 getCookieTokenByStoken / getLTokenByStoken，直接用已有的进入下一阶段。
                val needRefreshCookieToken = existingCookieToken == null
                val needRefreshLtoken = tokenInfo.ltoken.isBlank()
                if (needRefreshCookieToken || needRefreshLtoken) {
                    var newCookieToken = existingCookieToken
                    var newLtoken = tokenInfo.ltoken.ifBlank { null }
                    if (existingCookieToken == null) {
                        when (val r = mihoyoApi.getCookieTokenByStoken(tokenInfo.stoken, uid, mid)) {
                            is ApiResult.Success -> {
                                newCookieToken = r.data
                                setState { copy(debugInfo = (uiState.value.debugInfo ?: "") + "\ncookie_token 换取成功") }
                            }
                            is ApiResult.Error -> {
                                setState { copy(debugInfo = (uiState.value.debugInfo ?: "") + "\ncookie_token 换取失败: ${r.message}") }
                            }
                        }
                    }
                    if (tokenInfo.ltoken.isBlank()) {
                        when (val r = mihoyoApi.getLTokenByStoken(tokenInfo.stoken, uid, mid)) {
                            is ApiResult.Success -> {
                                newLtoken = r.data
                                setState { copy(debugInfo = (uiState.value.debugInfo ?: "") + "\nltoken 换取成功") }
                            }
                            is ApiResult.Error -> {
                                setState { copy(debugInfo = (uiState.value.debugInfo ?: "") + "\nltoken 换取失败: ${r.message}") }
                            }
                        }
                    }
                    // 把新换的 cookie_token/ltoken 补存（mid/cookieToken/ltoken 为 null 时不覆盖）
                    authRepository.saveLoginCredentials(
                        stoken = tokenInfo.stoken,
                        ltuid = uid,
                        mid = mid,
                        cookieToken = newCookieToken,
                        ltoken = newLtoken
                    )
                    setState { copy(statusText = "正在获取游戏角色...") }
                } else {
                    setState { copy(statusText = "已拿到所有凭证，正在获取游戏角色...") }
                }
                fetchGameRoles()
            }
            is ApiResult.Error -> {
                setState {
                    copy(
                        phase = AuthPhase.WEBVIEW_LOGIN,
                        error = "换取 stoken 失败: ${result.message}",
                        debugInfo = result.rawResponse.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    fun fetchGameRoles() {
        setState {
            copy(
                phase = AuthPhase.FETCHING_ROLES,
                statusText = "正在获取游戏角色...",
                error = null,
                debugInfo = "正在获取绑定的原神角色列表..."
            )
        }

        viewModelScope.launch {
            when (val result = mihoyoApi.getGameRoles()) {
                is ApiResult.Success -> {
                    val roles = result.data
                    when {
                        roles.isEmpty() -> setState {
                            copy(
                                phase = if (loginMethod == LoginMethod.QR_CODE) AuthPhase.QR_DISPLAY else AuthPhase.WEBVIEW_LOGIN,
                                qrBitmap = null,
                                error = "未找到绑定的原神角色，请先在米游社绑定游戏账号",
                                debugInfo = result.data.toString()
                            )
                        }
                        roles.size == 1 -> selectRole(roles[0])
                        else -> setState {
                            copy(
                                phase = AuthPhase.ROLE_SELECT,
                                gameRoles = roles,
                                debugInfo = null
                            )
                        }
                    }
                }
                is ApiResult.Error -> setState {
                    copy(
                        phase = if (loginMethod == LoginMethod.QR_CODE) AuthPhase.QR_DISPLAY else AuthPhase.WEBVIEW_LOGIN,
                        qrBitmap = null,
                        error = "获取角色失败: ${result.message}",
                        debugInfo = result.rawResponse.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
    }

    fun selectRole(role: GameRole) {
        setState {
            copy(
                phase = AuthPhase.GENERATING_KEY,
                selectedRole = role,
                statusText = "正在生成授权凭证...",
                error = null,
                debugInfo = "角色: ${role.nickname} (${role.uid})\n正在生成 authkey..."
            )
        }

        viewModelScope.launch {
            authRepository.saveGameRole(
                uid = role.uid,
                server = role.region,
                nickname = role.nickname
            )
            when (val result = mihoyoApi.generateAuthKey(role.uid, role.region)) {
                is ApiResult.Success -> {
                    // 通知全局：登录完成，其他 ViewModel 刷新数据
                    sessionEventBus.emit(SessionEvent.LoginCompleted)
                    setState {
                        copy(
                            phase = AuthPhase.DONE,
                            authKey = result.data,
                            statusText = "授权成功",
                            debugInfo = null
                        )
                    }
                }
                is ApiResult.Error -> setState {
                    copy(
                        phase = AuthPhase.ROLE_SELECT,
                        error = "生成授权凭证失败: ${result.message}",
                        debugInfo = result.rawResponse.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
    }

    fun refreshQrCode() {
        fetchQrCode()
    }

    fun clearError() {
        setState { copy(error = null) }
    }

    private fun parseCookies(cookieString: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        cookieString.split(";").forEach { pair ->
            val trimmed = pair.trim()
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (key.isNotBlank()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    private fun setState(reducer: AuthUiState.() -> AuthUiState) {
        _uiState.value = _uiState.value.reducer()
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
