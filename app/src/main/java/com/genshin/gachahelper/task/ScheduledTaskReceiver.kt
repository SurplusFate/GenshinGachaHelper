package com.genshin.gachahelper.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 固定时刻批量任务的闹钟回调：由 AlarmManager 到点后拉起，执行同步便笺 + 自动签到。
 *
 * 与 [com.genshin.gachahelper.signin.SignInBootReceiver] 一样走 @AndroidEntryPoint，
 * 由 Hilt 注入 [ScheduledTaskManager]；执行完任务内部会把自己（两个时刻）重新排上。
 */
@AndroidEntryPoint
class ScheduledTaskReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduledTaskManager: ScheduledTaskManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledTaskManager.ACTION_SCHEDULED_TASK) return
        val hour = intent.getIntExtra(
            ScheduledTaskManager.EXTRA_SLOT,
            ScheduledTaskManager.HOUR_AM_01
        )
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                scheduledTaskManager.runScheduledTask(hour)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
