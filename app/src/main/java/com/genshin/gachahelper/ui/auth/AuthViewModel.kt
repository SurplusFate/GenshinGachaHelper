package com.genshin.gachahelper.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.BuildConfig
import com.genshin.gachahelper.auth.ApiResult
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.auth.GameRole
import com.genshin.gachahelper.auth.MihoyoApiService
import com.genshin.gachahelper.auth.QrCodeData
import com.genshin.gachahelper.auth.QrCodeGenerator
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.repository.GachaRepository
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
    private val sessionEventBus: SessionEventBus,
    private val gachaRepository: GachaRepository
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
                phase = AuthPhase.LOADING,
                error = null,
                statusText = "正在获取二维码...",
                qrBitmap = null,
                debugInfo = null
            )
        }
        fetchQrCode()
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
                                val ltoken = result.data.ltoken
                                val cookieToken = result.data.cookieToken
                                // 新版通行证扫码登录的凭据来自响应头 Set-Cookie
                                // （ltoken_v2 / cookie_token_v2），stoken 可能为
                                // null。只要拿到 ltuid + 任一 token 即可继续。
                                if (!aid.isNullOrBlank() &&
                                    (!stoken.isNullOrBlank() ||
                                        !ltoken.isNullOrBlank() ||
                                        !cookieToken.isNullOrBlank())
                                ) {
                                    savePassportCredentialsAndFetchRoles(
                                        stoken = stoken,
                                        mid = mid,
                                        aid = aid,
                                        ltoken = ltoken,
                                        cookieToken = cookieToken
                                    )
                                } else {
                                    // 用户最新反馈："扫码确认成功 stoken=null"
                                    // —— 把这次失败的关键凭据 + Set-Cookie + tokens 完整记录
                                    com.genshin.gachahelper.auth.AppLog.e(
                                        "Auth", "ConfirmedNoCreds",
                                        "扫码确认成功但未拿到任何登录凭据\n" +
                                            "aid=$aid mid=${mid ?: "<empty>"}\n" +
                                            "stoken_present=${!stoken.isNullOrBlank()} " +
                                            "ltoken_present=${!ltoken.isNullOrBlank()} " +
                                            "cookieToken_present=${!cookieToken.isNullOrBlank()}\n" +
                                            "tokens_raw=${result.data.tokensRaw.take(300)}\n" +
                                            "Set-Cookie_lines=${result.data.setCookieHeader.lines().size} " +
                                            "first_2_lines=${result.data.setCookieHeader.lines().take(2).joinToString(" | ")}"
                                    )
                                    setState {
                                        copy(
                                            error = "扫码确认成功，但未获取到登录凭证\n" +
                                                "aid=$aid, mid=$mid\n" +
                                                "stoken=${stoken?.take(10) ?: "null"}, " +
                                                "ltoken=${ltoken?.take(10) ?: "null"}, " +
                                                "cookie_token=${cookieToken?.take(10) ?: "null"}\n" +
                                                "tokens=${result.data.tokensRaw.take(180)}\n" +
                                                "Set-Cookie: ${result.data.setCookieHeader.take(180)}",
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

    private fun savePassportCredentialsAndFetchRoles(
        stoken: String?,
        mid: String?,
        aid: String,
        ltoken: String? = null,
        cookieToken: String? = null
    ) {
        com.genshin.gachahelper.auth.AppLog.i(
            "Auth", "savePassportCredentials",
            "扫码确认 aid=$aid mid=${mid ?: "<empty>"} " +
                "stoken_present=${!stoken.isNullOrBlank()} " +
                "ltoken_present=${!ltoken.isNullOrBlank()} " +
                "cookieToken_present=${!cookieToken.isNullOrBlank()}"
        )
        setState {
            copy(
                phase = AuthPhase.EXCHANGING_TOKEN,
                statusText = "正在获取登录凭证...",
                error = null,
                debugInfo = "扫码确认成功\nstoken: ${stoken?.take(10) ?: "无"}\n" +
                    "ltoken: ${ltoken?.take(10) ?: "无"}\n" +
                    "cookie_token: ${cookieToken?.take(10) ?: "无"}\naid: $aid\nmid: ${mid ?: "无"}"
            )
        }

        viewModelScope.launch {
            // 先保存已拿到的凭据（不依赖 stoken，ltoken/cookie_token 亦可鉴权）
            authRepository.saveLoginCredentials(
                stoken = stoken,
                ltuid = aid,
                mid = mid,
                cookieToken = cookieToken,
                ltoken = ltoken
            )

            var finalCookieToken = cookieToken
            var finalLtoken = ltoken

            // 若已有 stoken，用它补齐缺失的 cookie_token / ltoken
            // （stoken 可用时这是最可靠的换取通道）
            if (!stoken.isNullOrBlank()) {
                if (finalCookieToken.isNullOrBlank()) {
                    when (val cookieResult = mihoyoApi.getCookieTokenByStoken(stoken, aid, mid)) {
                        is ApiResult.Success -> {
                            finalCookieToken = cookieResult.data
                            setState {
                                copy(
                                    debugInfo = "cookie_token 获取成功: ${finalCookieToken.take(16)}..."
                                )
                            }
                        }
                        is ApiResult.Error -> setState {
                            copy(
                                debugInfo = "cookie_token 获取失败: ${cookieResult.message}"
                            )
                        }
                    }
                }

                if (finalLtoken.isNullOrBlank()) {
                    when (val ltokenResult = mihoyoApi.getLTokenByStoken(stoken, aid, mid)) {
                        is ApiResult.Success -> {
                            finalLtoken = ltokenResult.data
                            setState {
                                copy(
                                    debugInfo = (uiState.value.debugInfo ?: "") +
                                        "\nltoken 获取成功: ${finalLtoken.take(16)}..."
                                )
                            }
                        }
                        is ApiResult.Error -> setState {
                            copy(
                                debugInfo = (uiState.value.debugInfo ?: "") +
                                    "\nltoken 获取失败: ${ltokenResult.message}"
                            )
                        }
                    }
                }

                authRepository.saveLoginCredentials(
                    stoken = stoken,
                    ltuid = aid,
                    mid = mid,
                    cookieToken = finalCookieToken,
                    ltoken = finalLtoken
                )
            } else {
                // 无 stoken（新版扫码）：Set-Cookie 里的 ltoken_v2 /
                // cookie_token_v2 已是可用凭证，直接进入角色获取
                setState {
                    copy(
                        debugInfo = (uiState.value.debugInfo ?: "") +
                            "\n无 stoken（新版扫码登录），使用 Set-Cookie 凭据继续"
                    )
                }
            }

            setState {
                copy(
                    statusText = "正在获取游戏角色...",
                    debugInfo = (uiState.value.debugInfo ?: "") + "\n正在获取角色列表..."
                )
            }

            fetchGameRoles()
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
                    com.genshin.gachahelper.auth.AppLog.i(
                        "Auth", "fetchGameRoles",
                        "成功 角色数=${result.data.size} " +
                            "uids=${result.data.joinToString { it.uid }}"
                    )
                    val roles = result.data
                    when {
                        roles.isEmpty() -> setState {
                            copy(
                                phase = AuthPhase.QR_DISPLAY,
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
                is ApiResult.Error -> {
                    com.genshin.gachahelper.auth.AppLog.e(
                        "Auth", "fetchGameRoles",
                        "失败 code=${result.code} step=${result.step} " +
                            "msg=${result.message} raw=${result.rawResponse.take(220)}"
                    )
                    setState {
                        copy(
                            phase = AuthPhase.QR_DISPLAY,
                            qrBitmap = null,
                            error = "获取角色失败: ${result.message}",
                            debugInfo = result.rawResponse.takeIf { it.isNotBlank() }
                        )
                    }
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
                debugInfo = "角色: ${role.nickname} (${role.uid})\n正在验证 UID..."
            )
        }

        viewModelScope.launch {
            // ===== UID 校验：先导入后登录场景 =====
            // 如果本地已有数据 UID，必须与登录 UID 一致才能绑定
            val localAccount = gachaRepository.getActiveAccount(null)
            val localDataUid = localAccount?.uid
            if (!localDataUid.isNullOrBlank() && localDataUid != role.uid) {
                setState {
                    copy(
                        phase = AuthPhase.ROLE_SELECT,
                        error = "UID 不一致：本地数据 UID 为 $localDataUid，登录账号 UID 为 ${role.uid}。\n" +
                            "禁止将现有数据绑定到该账号。请先清除本地数据或使用 UID 为 $localDataUid 的账号登录。",
                        debugInfo = "UID 校验失败：local=$localDataUid, login=${role.uid}"
                    )
                }
                return@launch
            }

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

    private fun setState(reducer: AuthUiState.() -> AuthUiState) {
        val next = _uiState.value.reducer()
        // debugInfo 会带上 stoken / cookie_token / authkey 等凭证片段，仅供开发期排查；
        // release 构建一律剥离，避免随日志或用户截图外泄。
        _uiState.value = if (BuildConfig.DEBUG) next else next.copy(debugInfo = null)
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
