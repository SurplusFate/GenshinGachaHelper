package com.genshin.gachahelper.signin

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.genshin.gachahelper.auth.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.signInDataStore by preferencesDataStore(name = "signin_store")

/**
 * 每日签到仓库
 *
 * 职责：
 * 1. 持久化「每日自动签到」开关（DataStore）
 * 2. 通过 WorkManager 调度每日定时签到（OneTimeWork 链：每次执行完成后重排下一天）
 * 3. 执行签到并把结果通过通知/StateFlow 反馈给用户
 *
 * 调度策略：Worker 执行完成后由 [scheduleNext] 重新排队下一天，
 * 避免 PeriodicWorkRequest 粒度无法指定到精确时刻的问题。
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

        /** 记录启用时对应的账号 uid，账号切换时可提示重新确认 */
        private val KEY_UID = stringPreferencesKey("daily_signin_uid")

        private const val UNIQUE_WORK_NAME = "daily_signin_work"

        private const val SIGN_CHANNEL_ID = "daily_signin"
        private const val NOTIFICATION_ID = 10086

        /** 每日签到时刻（北京时间） */
        private val SIGN_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private const val SIGN_HOUR = 8
        private const val SIGN_MINUTE = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lastResult = MutableStateFlow<String?>(null)

    /** 最近一次签到结果提示（手动/自动共用） */
    val lastResult: MutableStateFlow<String?> = _lastResult

    /** 自动签到开关 */
    val enabledFlow: Flow<Boolean> = context.signInDataStore.data
        .map { it[KEY_ENABLED] ?: false }

    suspend fun isEnabled(): Boolean =
        context.signInDataStore.data.first()[KEY_ENABLED] ?: false

    /**
     * 设置自动签到开关；开启时立即调度（次日生效），关闭时取消未执行任务
     */
    fun setEnabled(enabled: Boolean) {
        scope.launch {
            context.signInDataStore.edit { prefs ->
                prefs[KEY_ENABLED] = enabled
                if (enabled) {
                    prefs[KEY_UID] = authRepository.getUid() ?: ""
                }
            }
            if (enabled) {
                ensureChannel()
                if (authRepository.isLoggedIn()) {
                    scheduleNext()
                } else {
                    _lastResult.value = "自动签到已开启，等待登录后次日生效"
                }
            } else {
                cancelScheduled()
                _lastResult.value = "已关闭每日自动签到"
            }
        }
    }

    /** 取消已排队的签到任务 */
    private fun cancelScheduled() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * 排队下一次签到（次日 SIGN_HOUR:SIGN_MINUTE 北京时间）
     */
    fun scheduleNext() {
        val delayMs = nextDelayMillis()
        if (delayMs < 0) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DailySignInWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** 距下次目标时刻的毫秒数 */
    private fun nextDelayMillis(now: Instant = Instant.now()): Long {
        val nowZone = ZonedDateTime.ofInstant(now, SIGN_ZONE)
        var next = nowZone.toLocalDate()
            .atTime(SIGN_HOUR, SIGN_MINUTE)
            .atZone(SIGN_ZONE)
        if (!next.isAfter(nowZone)) {
            next = next.plusDays(1)
        }
        return next.toInstant().toEpochMilli() - now.toEpochMilli()
    }

    // ------------------------------------------------------------------
    // 签到执行
    // ------------------------------------------------------------------

    /**
     * 执行一次完整签到流程（供 Worker 调用，自动续排）
     * @return 用户可读结果文案
     */
    suspend fun performDailySignIn(): String {
        val result = doSignIn()
        _lastResult.value = result.message
        notify(result.message)
        // 无论成功失败都续排下一天（除非登录失效由 doSignIn 处理）
        if (result.keepScheduled) {
            scheduleNext()
        }
        return result.message
    }

    /**
     * 手动立即签到（供设置页按钮调用；不重排自动任务）
     */
    suspend fun performManualSignIn(): String {
        val result = doSignIn()
        _lastResult.value = result.message
        notify(result.message)
        return result.message
    }

    private data class SignOutcome(
        val message: String,
        val keepScheduled: Boolean = true
    )

    private suspend fun doSignIn(): SignOutcome {
        // 1. 校验登录态
        if (!authRepository.isLoggedIn()) {
            cancelScheduled()
            return SignOutcome("未登录，已暂停自动签到，请重新登录后开启", keepScheduled = false)
        }
        val uid = authRepository.getUid()
        val region = authRepository.getServer()
        if (uid.isNullOrBlank() || region.isNullOrBlank()) {
            cancelScheduled()
            return SignOutcome("缺少游戏账号信息(uid/region)，已暂停自动签到", keepScheduled = false)
        }

        // 2. 查询今日状态：已签到则直接结束
        when (val info = signInApi.getSignInfo(uid, region)) {
            is com.genshin.gachahelper.auth.ApiResult.Success -> {
                if (info.data.isSign) {
                    return SignOutcome(
                        "今日已签到（UID $uid 本月累计 ${info.data.totalSignDay} 天）"
                    )
                }
            }
            is com.genshin.gachahelper.auth.ApiResult.Error -> {
                // 状态查询失败不阻塞签到流程，直接尝试 sign
            }
        }

        // 3. 执行签到
        return when (val result = signInApi.sign(uid, region)) {
            is com.genshin.gachahelper.auth.ApiResult.Success -> {
                val r = result.data
                when {
                    r.alreadySigned -> SignOutcome(
                        "今日已签到（UID $uid）${r.message.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}"
                    )
                    r.isRisk -> SignOutcome(
                        "签到触发风控校验，请打开米游社 App 手动签到（UID $uid）"
                    )
                    else -> SignOutcome("签到成功（UID $uid）：${r.message}")
                }
            }
            is com.genshin.gachahelper.auth.ApiResult.Error -> {
                val err = result
                SignOutcome(
                    "签到失败（UID $uid）：${err.message}${err.step.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""}"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

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
