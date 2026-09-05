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
 * 由 SignInRepository 通过 WorkManager 调度，每天执行一次：
 * 1. 检查自动签到开关，已关闭直接结束
 * 2. 执行签到（状态查询 + 签到）
 * 3. 通知用户结果，并由 Repository 续排次日任务
 */
@HiltWorker
class DailySignInWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val signInRepository: SignInRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 开关被关闭时，静默结束（任务已被 Repository 取消，此处兜底）
        if (!signInRepository.isEnabled()) {
            return Result.success()
        }
        signInRepository.performDailySignIn()
        return Result.success()
    }
}
