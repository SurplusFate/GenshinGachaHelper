package com.genshin.gachahelper.ui.auth

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
                            debugInfo = "cookie_token 获取成功: ${cookieToken.take(16)}...\n正在换取 ltoken..."
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
                            debugInfo = (uiState.value.debugInfo ?: "") + "\nltoken 获取成功: ${ltoken.take(16)}..."
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
                is ApiResult.Error -> setState {
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
        _uiState.value = _uiState.value.reducer()
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
