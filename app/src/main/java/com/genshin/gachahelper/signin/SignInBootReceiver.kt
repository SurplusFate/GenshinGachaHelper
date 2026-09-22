package com.genshin.gachahelper.signin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.genshin.gachahelper.reminder.ResinReminderManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机广播接收器：设备重启后恢复每日自动签到。
 *
 * 收到 BOOT_COMPLETED 后由 SignInRepository 完成：
 * 开关开启且今日未签到 → 立即补签，并确保 24h 周期任务重新进入队列；
 * 未登录 / 账号不一致等 → 保持暂停并提示用户处理。
 *
 * 2026-09-22 追加：开机后 AlarmManager 中已排的闹钟全部失效，
 * 这里顺带按本地便笺快照重排一次树脂/洞天宝钱阈值提醒。
 */
@AndroidEntryPoint
class SignInBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var signInRepository: SignInRepository

    @Inject
    lateinit var reminderManager: ResinReminderManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        signInRepository.onLaunchOrBoot()
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                reminderManager.reschedule()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
