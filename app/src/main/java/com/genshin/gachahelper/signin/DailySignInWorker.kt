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
 *
 * 通知：执行完由 [SignInRepository.publishAutoResult] 按结果补发系统通知
 * （2026-09-24 修复：此前本 Worker 从不发通知，后台自动签到全程静默）。
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
        // 后台自动签到是"用户不在场"的场景：进展性结果（签到成功 / 风控 / 失败）必须发系统通知，
        // 否则用户看不到任何反馈。"今日已签到"与"网络重试"由 publishAutoResult 内部过滤，不打扰。
        signInRepository.publishAutoResult(outcome)
        return if (outcome.state == AutoState.RETRY) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
