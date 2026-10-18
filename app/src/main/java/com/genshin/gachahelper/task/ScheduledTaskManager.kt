package com.genshin.gachahelper.task

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.genshin.gachahelper.auth.ApiResult
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.auth.DailyNoteService
import com.genshin.gachahelper.reminder.ResinReminderManager
import com.genshin.gachahelper.reminder.ResinReminderStore
import com.genshin.gachahelper.signin.SignInRepository
import com.genshin.gachahelper.ui.home.DailyNoteRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 固定时刻批量任务（2026-10-16 新增）。
 *
 * 用户诉求：在「凌晨 1 点」与「下午 1 点」两个固定时刻，自动完成三件事——
 *   ① 同步树脂（每日便笺）；② 同步洞天宝钱（同一份便笺接口，一次拉取同时覆盖）；
 *   ③ 自动签到（沿用既有自动签到开关与幂等逻辑）。
 *
 * 设计：
 * - 用 AlarmManager.setAlarmClock 排两个一次性精确闹钟（与树脂阈值提醒同款高存活闹钟，
 *   能抵御国产 ROM 的清后台/强杀）。到点由 [ScheduledTaskReceiver] 拉起本 Manager 执行任务，
 *   执行完再把自己（两个时刻）重新排上，因为 setAlarmClock 是一次性触发。
 * - 两个时刻使用不同的 requestCode，互不干扰；重复调用 [scheduleAll] 因 FLAG_UPDATE_CURRENT
 *   而幂等，可安全地在「App 启动 / 开机 / 每次到点」都重排。
 * - 同步逻辑直接复用 [DailyNoteService.fetchDailyNote] + [DailyNoteRepository.save]，
 *   与首页「每日便笺」完全同一套数据源，落盘后顺手按最新锚点重排阈值提醒。
 * - 自动签到复用 [SignInRepository.performAutoSignIn]，其自身已做「今日是否已签」幂等判断，
 *   故 13:00 那次即便 01:00 已签过也是安全的 no-op。
 *   注：本任务只触发签到逻辑、不单独弹通知（既有 24h 的 DailySignInWorker 负责用户侧通知），
 *   避免两套调度在同一天重复打扰。
 */
@Singleton
class ScheduledTaskManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val dailyNoteService: DailyNoteService,
    private val dailyNoteRepository: DailyNoteRepository,
    private val reminderStore: ResinReminderStore,
    private val reminderManager: ResinReminderManager,
    private val signInRepository: SignInRepository
) {
    companion object {
        const val ACTION_SCHEDULED_TASK = "com.genshin.gachahelper.action.SCHEDULED_TASK"
        const val EXTRA_SLOT = "slot_hour"

        private const val REQUEST_CODE_01 = 4201
        private const val REQUEST_CODE_13 = 4202

        /** 固定时刻（24h 制） */
        const val HOUR_AM_01 = 1
        const val HOUR_PM_13 = 13
    }

    /** 排两个固定时刻闹钟（幂等，可重复调用） */
    fun scheduleAll() {
        scheduleSlot(HOUR_AM_01, REQUEST_CODE_01)
        scheduleSlot(HOUR_PM_13, REQUEST_CODE_13)
    }

    private fun scheduleSlot(hour: Int, requestCode: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerAt(hour)
        val pi = pendingIntent(hour, requestCode)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            // 优先 setAlarmClock：高存活，清后台也能到点拉起；状态栏会显示到点闹钟图标（点到即消）。
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
        } catch (_: SecurityException) {
            try {
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (_: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    private fun pendingIntent(hour: Int, requestCode: Int): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java)
            .setAction(ACTION_SCHEDULED_TASK)
            .putExtra(EXTRA_SLOT, hour)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 计算下一个目标时刻（今日已过则顺延到明天） */
    private fun nextTriggerAt(hour: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return cal.timeInMillis
    }

    /**
     * 闹钟到点执行：同步便笺（树脂 + 洞天宝钱）+ 自动签到，执行完重排下一次。
     * try/finally 保证即使任务抛异常，下一次排程也不会断（否则当天之后永不触发）。
     */
    suspend fun runScheduledTask(hour: Int) {
        try {
            syncDailyNote()
            if (signInRepository.isEnabled()) {
                signInRepository.performAutoSignIn()
            }
        } finally {
            scheduleAll()
        }
    }

    /** 同步每日便笺：一次接口同时拿树脂与洞天宝钱，落盘为当天推算锚点 */
    private suspend fun syncDailyNote() {
        if (!authRepository.isLoggedIn()) return
        val uid = authRepository.getUid()?.takeIf { it.isNotBlank() } ?: return
        val homeCoinPerHour = reminderStore.current().homeCoinPerHour
        when (val result = dailyNoteService.fetchDailyNote()) {
            is ApiResult.Success -> {
                val fresh = result.data.copy(homeCoinPerHour = homeCoinPerHour)
                dailyNoteRepository.save(uid, fresh)
                // 新快照落地后按最新锚点重排阈值提醒
                reminderManager.reschedule()
            }
            is ApiResult.Error -> {
                // 网络失败：保留上次成功快照，不静默清空；后台任务静默即可，不弹通知。
            }
        }
    }
}
