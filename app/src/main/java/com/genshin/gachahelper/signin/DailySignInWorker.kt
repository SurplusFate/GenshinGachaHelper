package com.genshin.gachahelper.signin

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 每日签到 Worker
 *
 * 由 SignInRepository 以 24h 宽松周期任务注册（不绑定固定时刻）。
 * 每次执行：检查自动签到开关 → 查询今日是否已签到 → 未签到则尝试签到。
 *
 * 返回策略：
 * - 网络等瞬时失败（[AutoState.RETRY]）→ [Result.retry] 交给 WorkManager 指数退避重试；
 * - 其余情况（成功 / 今日已签 / 普通失败 / 登录失效已暂停）→ [Result.success]。
 */
@HiltWorker
class DailySignInWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val signInRepository: SignInRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 开关被关闭时，静默结束（周期任务通常已被取消，此处兜底）
        if (!signInRepository.isEnabled()) {
            return Result.success()
        }
        val outcome = signInRepository.performAutoSignIn()
        return if (outcome.state == AutoState.RETRY) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
