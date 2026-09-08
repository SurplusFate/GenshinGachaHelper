package com.genshin.gachahelper.signin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.genshin.gachahelper.auth.ApiResult
import com.genshin.gachahelper.auth.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.signInDataStore by preferencesDataStore(name = "signin_store")

/**
 * 自动签到单次执行后的状态。
 *
 * 产品语义：自动签到 = 每日签到保障（每天成功一次即可），不是定时签到。
 */
enum class AutoState {
    /** 本轮处理结束：成功签到 / 今日已签到 / 普通业务失败（无需立即重试） */
    DONE,

    /** 网络等瞬时失败：交由 WorkManager 退避重试（自动签到开关保持开启） */
    RETRY,

    /** 登录失效 / 账号与绑定 UID 不一致：自动签到已暂停，等待用户处理 */
    PAUSED
}

/** 自动签到单次执行结果 */
data class AutoSignInResult(
    val message: String,
    val state: AutoState
)

/**
 * 每日签到仓库
 *
 * 职责：
 * 1. 持久化「每日自动签到」开关与开启时绑定的账号 UID（DataStore）
 * 2. 通过 WorkManager 注册 24h 宽松周期任务：任务执行时检查「今天是否已签到」，
 *    未签到则尝试签到；不要求任何固定时刻（不再使用 08:00 精确调度）
 * 3. App 启动 / 回到前台 / 设备重启时补偿：开关开启且今日未签到 → 立即补签，
 *    同时确保后台周期任务在队列中
 * 4. 失败分级：网络瞬时失败由 WorkManager 退避重试；登录失效或切换账号
 *    则暂停自动签到（取消周期任务）并提示用户处理
 */
@Singleton
class SignInRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val signInApi: SignInApi,
    private val workManager: WorkManager
) {
    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("daily_signin_enabled")

        /** 记录开启自动签到时的账号 uid，切换账号时防止给错误账号签到 */
        private val KEY_UID = stringPreferencesKey("daily_signin_uid")

        private const val UNIQUE_WORK_NAME = "daily_signin_work"

        private const val SIGN_CHANNEL_ID = "daily_signin"
        private const val NOTIFICATION_ID = 10086

        /** 后台周期任务间隔：24h。不要求精确执行时刻，系统自行安排当天运行时机 */
        private const val PERIODIC_INTERVAL_HOURS = 24L

        /** 登录失效等会话错误返回码（与全项目 GachaResponseParser 判定口径一致） */
        private const val CODE_SESSION_INVALID = -100

        /** 补偿入口防抖间隔，避免 Activity 频繁回前台时发起多余请求 */
        private const val COMPENSATE_THROTTLE_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastCompensateAt = AtomicLong(0)

    private val _lastResult = MutableStateFlow<String?>(null)

    /** 最近一次签到结果提示（手动/自动共用） */
    val lastResult: MutableStateFlow<String?> = _lastResult

    /** 自动签到开关 */
    val enabledFlow: Flow<Boolean> = context.signInDataStore.data
        .map { it[KEY_ENABLED] ?: false }

    suspend fun isEnabled(): Boolean =
        context.signInDataStore.data.first()[KEY_ENABLED] ?: false

    // ------------------------------------------------------------------
    // 开关与调度
    // ------------------------------------------------------------------

    /**
     * 设置自动签到开关。
     *
     * 开启：绑定当前 UID → 注册 24h 周期后台任务 → 立即获得一次签到机会
     * （今日未签到则补签）；关闭：取消周期任务。
     */
    fun setEnabled(enabled: Boolean) {
        scope.launch {
            context.signInDataStore.edit { prefs ->
                prefs[KEY_ENABLED] = enabled
                if (enabled) {
                    prefs[KEY_UID] = authRepository.getUid() ?: ""
                }
            }
            ensureChannel()
            if (enabled) {
                if (authRepository.isLoggedIn()) {
                    ensureScheduled()
                    val r = performAutoSignIn()
                    publish(r.message, notifyUser = r.state != AutoState.RETRY)
                } else {
                    cancelScheduled()
                    publish("自动签到已开启，等待登录后生效")
                }
            } else {
                cancelScheduled()
                publish("已关闭每日自动签到")
            }
        }
    }

    /**
     * App 启动 / 回到前台 / 设备重启后的补偿入口。
     *
     * 幂等：先检查开关与今日签到状态，今日已签到不会重复请求签到；
     * 带 30s 防抖避免高频触发。校验未通过（未登录 / 账号不匹配）会暂停并提示。
     */
    fun onLaunchOrBoot() {
        val now = System.currentTimeMillis()
        if (now - lastCompensateAt.get() < COMPENSATE_THROTTLE_MS) return
        lastCompensateAt.set(now)
        scope.launch { checkAndRestore() }
    }

    /** 取消周期签到任务（暂停场景使用） */
    private fun cancelScheduled() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /** 注册 / 刷新 24h 周期签到任务（宽松执行，不绑定固定时刻） */
    private fun ensureScheduled() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<DailySignInWorker>(
            PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * 补偿检查：开关开 → 校验登录态 → 确保周期任务在队列 →
     * 今日未签到则立即补签。
     */
    private suspend fun checkAndRestore() {
        if (!isEnabled()) return
        if (!authRepository.isLoggedIn()) {
            cancelScheduled()
            publish("自动签到已暂停：未登录，请先登录米游社账号")
            return
        }
        val uid = authRepository.getUid()
        val region = authRepository.getServer()
        if (uid.isNullOrBlank() || region.isNullOrBlank()) {
            cancelScheduled()
            publish("自动签到已暂停：缺少游戏账号信息(uid/region)，请重新登录")
            return
        }
        ensureScheduled()
        val r = performAutoSignIn()
        publish(r.message, notifyUser = r.state != AutoState.RETRY)
    }

    // ------------------------------------------------------------------
    // 自动签到执行
    // ------------------------------------------------------------------

    /**
     * 自动签到一次（供 Worker / 补偿 / 开启瞬间共用）。
     *
     * 内部完成：开关检查 → 登录态检查 → 绑定 UID 校验 →
     * 今日状态查询（已签到不重复请求）→ 签到 → 失败分级。
     */
    suspend fun performAutoSignIn(): AutoSignInResult {
        if (!isEnabled()) {
            return AutoSignInResult("自动签到已关闭", AutoState.DONE)
        }
        // 1. 登录态
        if (!authRepository.isLoggedIn()) {
            cancelScheduled()
            return AutoSignInResult(
                "自动签到已暂停：未登录，请重新登录米游社账号",
                AutoState.PAUSED
            )
        }
        val uid = authRepository.getUid()
        val region = authRepository.getServer()
        if (uid.isNullOrBlank() || region.isNullOrBlank()) {
            cancelScheduled()
            return AutoSignInResult(
                "自动签到已暂停：缺少游戏账号信息(uid/region)，请重新登录",
                AutoState.PAUSED
            )
        }
        // 2. 绑定 UID 校验：防止切换账号后给新账号签到
        val bindMsg = checkBinding(uid)
        if (bindMsg != null) {
            cancelScheduled()
            return AutoSignInResult(bindMsg, AutoState.PAUSED)
        }

        // 3. 查询今日状态：已签到直接结束，不重复发签到请求
        when (val info = signInApi.getSignInfo(uid, region)) {
            is ApiResult.Success -> {
                if (info.data.isSign) {
                    return AutoSignInResult(
                        "今日已签到（UID $uid 本月累计 ${info.data.totalSignDay} 天）",
                        AutoState.DONE
                    )
                }
            }
            is ApiResult.Error -> {
                // 状态查询失败不阻塞，直接尝试签到（retcode=-100 会在 sign 阶段统一拦截）
            }
        }

        // 4. 执行签到
        return when (val result = signInApi.sign(uid, region)) {
            is ApiResult.Success -> {
                val r = result.data
                when {
                    r.alreadySigned -> AutoSignInResult(
                        "今日已签到（UID $uid）" +
                            (r.message.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""),
                        AutoState.DONE
                    )
                    r.isRisk -> AutoSignInResult(
                        "签到触发风控校验，请打开米游社 App 手动签到（UID $uid）",
                        AutoState.DONE
                    )
                    else -> AutoSignInResult("签到成功（UID $uid）：${r.message}", AutoState.DONE)
                }
            }
            is ApiResult.Error -> classifyAutoError(result, uid)
        }
    }

    /**
     * 校验 / 迁移绑定 UID。
     * @return null 表示校验通过；非 null 为暂停原因文案（需取消周期任务并提示用户）。
     */
    private suspend fun checkBinding(currentUid: String): String? {
        val bound = context.signInDataStore.data.first()[KEY_UID]
        if (bound.isNullOrBlank()) {
            // 旧版本开启时未绑定 UID → 以当前账号补绑定（兼容迁移）
            context.signInDataStore.edit { it[KEY_UID] = currentUid }
            return null
        }
        if (bound != currentUid) {
            return "自动签到已暂停：当前账号(UID $currentUid)与开启时绑定的账号(UID $bound)不一致，" +
                "已停止自动签到，请确认账号或重新开启自动签到"
        }
        return null
    }

    /** 自动签到失败分级（仅自动流程使用：可暂停 / 可重试 / 普通失败） */
    private fun classifyAutoError(err: ApiResult.Error, uid: String): AutoSignInResult {
        val detail = err.message +
            (err.step.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: "")
        // 登录 / 会话失效 → 暂停自动签到并提示重新登录
        if (err.code == CODE_SESSION_INVALID) {
            cancelScheduled()
            return AutoSignInResult(
                "签到失败（UID $uid）：$detail；登录已失效，自动签到已暂停，请重新登录",
                AutoState.PAUSED
            )
        }
        // 网络等瞬时错误 → 交给 WorkManager 退避重试（开关保持开启）
        if (err.code < 0) {
            return AutoSignInResult(
                "签到失败（UID $uid）：$detail，稍后自动重试",
                AutoState.RETRY
            )
        }
        // 普通业务失败 → 保留开关，等下次周期 / 补偿机会再尝试
        return AutoSignInResult("签到失败（UID $uid）：$detail", AutoState.DONE)
    }

    /**
     * 手动立即签到（供设置页按钮调用）。
     * 明确针对当前登录账号，不校验绑定 UID，也不影响后台周期任务。
     */
    suspend fun performManualSignIn(): String {
        val message = manualSignInOnce()
        _lastResult.value = message
        notify(message)
        return message
    }

    private suspend fun manualSignInOnce(): String {
        if (!authRepository.isLoggedIn()) {
            return "未登录，请先登录米游社账号"
        }
        val uid = authRepository.getUid()
        val region = authRepository.getServer()
        if (uid.isNullOrBlank() || region.isNullOrBlank()) {
            return "缺少游戏账号信息(uid/region)，请重新登录"
        }
        when (val info = signInApi.getSignInfo(uid, region)) {
            is ApiResult.Success -> {
                if (info.data.isSign) {
                    return "今日已签到（UID $uid 本月累计 ${info.data.totalSignDay} 天）"
                }
            }
            is ApiResult.Error -> Unit
        }
        return when (val result = signInApi.sign(uid, region)) {
            is ApiResult.Success -> {
                val r = result.data
                when {
                    r.alreadySigned -> "今日已签到（UID $uid）" +
                        (r.message.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
                    r.isRisk -> "签到触发风控校验，请打开米游社 App 手动签到（UID $uid）"
                    else -> "签到成功（UID $uid）：${r.message}"
                }
            }
            is ApiResult.Error -> {
                val err = result
                "签到失败（UID $uid）：${err.message}" +
                    (err.step.takeIf { it.isNotBlank() }?.let { " [${it}]" } ?: "")
            }
        }
    }

    // ------------------------------------------------------------------
    // 结果展示与通知
    // ------------------------------------------------------------------

    private fun publish(message: String, notifyUser: Boolean = true) {
        _lastResult.value = message
        if (notifyUser) notify(message)
    }

    /** 创建签到通知渠道（幂等） */
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            SIGN_CHANNEL_ID,
            "每日签到",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "米游社每日自动签到结果"
        }
        manager.createNotificationChannel(channel)
    }

    private fun notify(message: String) {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return

        ensureChannel()
        val notification = NotificationCompat.Builder(context, SIGN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("每日签到")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 无通知权限时静默失败，不影响签到逻辑
        }
    }
}
