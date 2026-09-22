package com.genshin.gachahelper.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.genshin.gachahelper.ui.home.DailyNoteRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 树脂 / 洞天宝钱阈值提醒（2026-09-22 新增）。
 *
 * 用户诉求：不再只能"等回满"，而是可以自定义达到某个数值后提醒。
 *
 * 设计要点：
 * 1. 配置入口以设置页为主体（完整规则），便笺卡片只放轻量快捷入口，两端读写同一份 DataStore；
 * 2. 提醒时刻由本地外推公式直接算出「预计到达阈值的绝对时刻」，交给 AlarmManager
 *    排一次性精确闹钟——App 被划掉/后台冻结也能按时响（配合每点恢复速率是确定值）；
 * 3. 进程活着时每次拿到便笺快照 / 改配置都会重排闹钟，保证锚点最新。
 */

/** 提醒配置：设置页与便笺卡片共用 */
data class ReminderConfig(
    val enabled: Boolean = false,
    /** 树脂达到该值时提醒（1..maxResin，默认 120；设为 200 即"回满提醒"） */
    val resinThreshold: Int = 120,
    /** 是否同时提醒洞天宝钱 */
    val homeCoinEnabled: Boolean = false,
    val homeCoinThreshold: Int = 2000,
    /** true=每种资源每天最多提醒一次；false=每次达到都提醒（10 分钟防抖） */
    val oncePerDay: Boolean = true
) {
    companion object {
        /** 洞天宝钱上限（接口返回 maxHomeCoin，兜底值） */
        const val DEFAULT_HOME_COIN_MAX = 2400

        /** 树脂上限（接口返回 maxResin，兜底值 200） */
        const val DEFAULT_RESIN_MAX = 200
    }
}

private val Context.resinReminderStore by preferencesDataStore(name = "resin_reminder_store")

/** 提醒配置与"上次提醒时间"的持久化 */
@Singleton
class ResinReminderStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_RESIN_THRESHOLD = intPreferencesKey("resin_threshold")
        private val KEY_COIN_ENABLED = booleanPreferencesKey("home_coin_enabled")
        private val KEY_COIN_THRESHOLD = intPreferencesKey("home_coin_threshold")
        private val KEY_ONCE_PER_DAY = booleanPreferencesKey("once_per_day")
        private val KEY_LAST_RESIN_AT = longPreferencesKey("last_resin_notify_at")
        private val KEY_LAST_COIN_AT = longPreferencesKey("last_coin_notify_at")

        const val RESIN = "resin"
        const val HOME_COIN = "home_coin"
    }

    val configFlow: Flow<ReminderConfig> = context.resinReminderStore.data.map { prefs ->
        val defaults = ReminderConfig()
        ReminderConfig(
            enabled = prefs[KEY_ENABLED] ?: defaults.enabled,
            resinThreshold = prefs[KEY_RESIN_THRESHOLD] ?: defaults.resinThreshold,
            homeCoinEnabled = prefs[KEY_COIN_ENABLED] ?: defaults.homeCoinEnabled,
            homeCoinThreshold = prefs[KEY_COIN_THRESHOLD] ?: defaults.homeCoinThreshold,
            oncePerDay = prefs[KEY_ONCE_PER_DAY] ?: defaults.oncePerDay
        )
    }

    suspend fun current(): ReminderConfig = configFlow.first()

    suspend fun save(config: ReminderConfig) {
        context.resinReminderStore.edit { prefs ->
            prefs[KEY_ENABLED] = config.enabled
            prefs[KEY_RESIN_THRESHOLD] = config.resinThreshold
            prefs[KEY_COIN_ENABLED] = config.homeCoinEnabled
            prefs[KEY_COIN_THRESHOLD] = config.homeCoinThreshold
            prefs[KEY_ONCE_PER_DAY] = config.oncePerDay
        }
    }

    /** 上次提醒时刻（毫秒），0 表示从未提醒 */
    suspend fun lastNotifiedAt(resource: String): Long =
        context.resinReminderStore.data.first()[lastKey(resource)] ?: 0L

    suspend fun markNotified(resource: String, at: Long = System.currentTimeMillis()) {
        context.resinReminderStore.edit { it[lastKey(resource)] = at }
    }

    private fun lastKey(resource: String) =
        if (resource == HOME_COIN) KEY_LAST_COIN_AT else KEY_LAST_RESIN_AT

    /**
     * 今天是否已经提醒过 [resource]。
     *
     * 注意：这里只按自然日判断，"是否允许再次提醒"的完整语义由
     * [ResinReminderManager.shouldNotify] 结合 oncePerDay 决定。
     */
    suspend fun notifiedToday(resource: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = lastNotifiedAt(resource)
        return last > 0L && DailyNoteRepository.isSameLocalDay(last, now)
    }
}

/** 提醒通知渠道与发送（独立渠道，用户可单独静音） */
object ResinReminderNotifier {
    private const val CHANNEL_ID = "resin_reminder"
    private const val RESIN_NOTIFICATION_ID = 2001
    private const val COIN_NOTIFICATION_ID = 2002

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "树脂提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "树脂 / 洞天宝钱达到自定义阈值时提醒"
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyResin(context: Context, current: Int, threshold: Int) {
        val text = if (current >= threshold) {
            "树脂已达到 $current（阈值 $threshold），去清体力吧"
        } else {
            "树脂已达阈值 $threshold"
        }
        send(context, RESIN_NOTIFICATION_ID, "树脂提醒", text)
    }

    fun notifyHomeCoin(context: Context, current: Int, threshold: Int) {
        send(context, COIN_NOTIFICATION_ID, "洞天宝钱提醒", "洞天宝钱已达到 $current（阈值 $threshold），去收钱吧")
    }

    private fun send(context: Context, id: Int, title: String, text: String) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 无通知权限时静默失败，不影响其他逻辑
        }
    }
}

/**
 * 提醒调度中枢：读快照 → 算"到达阈值的绝对时刻" → 排精确闹钟；闹钟到点后判定并通知、再重排。
 *
 * 进程被杀时只有闹钟能叫醒我们，因此 [reschedule] 必须在
 * "拿到新快照 / 改配置 / 开机 / 闹钟触发后"都被调用。
 */
@Singleton
class ResinReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ResinReminderStore,
    private val dailyNoteRepository: DailyNoteRepository
) {
    companion object {
        const val ACTION_REMINDER = "com.genshin.gachahelper.action.RESIN_REMINDER"
        private const val REQUEST_CODE = 4101

        /** 已达标但还没提醒过时，延迟这么久发（给"立即提醒"留出进程启动时间） */
        private const val IMMEDIATE_DELAY_MS = 3_000L

        /** oncePerDay=false（每次达到都提醒）场景的最小提醒间隔，防止同值反复触发 */
        private const val MIN_REPEAT_INTERVAL_MS = 10 * 60_000L
    }

    /**
     * 保存配置并按新阈值重排闹钟：设置页与便笺卡片共用同一入口，
     * 避免两处各写一套"落库 + 排程"逻辑。
     */
    suspend fun update(transform: (ReminderConfig) -> ReminderConfig) {
        store.save(transform(store.current()))
        reschedule()
    }

    /** 便笺快照更新 / 配置变更 / 开机后调用：按最新锚点重排下一次提醒 */
    suspend fun reschedule() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        cancel()
        val config = store.current()
        if (!config.enabled) return

        val note = dailyNoteRepository.loadLatest() ?: return
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Long>()

        run {
            val reachAt = note.resinReachAt(config.resinThreshold, now)
            if (reachAt != null && shouldNotify(ResinReminderStore.RESIN, config, reachAt, now)) {
                candidates += reachAt
            }
        }
        if (config.homeCoinEnabled) {
            val reachAt = note.homeCoinReachAt(config.homeCoinThreshold, now)
            if (reachAt != null && shouldNotify(ResinReminderStore.HOME_COIN, config, reachAt, now)) {
                candidates += reachAt
            }
        }

        val next = candidates.minOrNull() ?: return
        val triggerAt = if (next <= now) now + IMMEDIATE_DELAY_MS else next
        setAlarm(alarmManager, triggerAt)
    }

    /** 取消已排的提醒（关闭开关 / 退出登录时调用） */
    fun cancel() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent())
    }

    /** 闹钟到点：判定是否真的达标并通知，然后按新锚点重排 */
    suspend fun onAlarmFired() {
        val config = store.current()
        if (!config.enabled) return
        val note = dailyNoteRepository.loadLatest() ?: return
        val now = System.currentTimeMillis()

        val resinProjection = note.resinAt(now)
        if (shouldNotify(ResinReminderStore.RESIN, config, now, now) &&
            resinProjection.value >= config.resinThreshold
        ) {
            ResinReminderNotifier.notifyResin(context, resinProjection.value, config.resinThreshold)
            store.markNotified(ResinReminderStore.RESIN, now)
        }

        if (config.homeCoinEnabled) {
            val coinProjection = note.homeCoinAt(now)
            if (shouldNotify(ResinReminderStore.HOME_COIN, config, now, now) &&
                coinProjection.value >= config.homeCoinThreshold
            ) {
                ResinReminderNotifier.notifyHomeCoin(context, coinProjection.value, config.homeCoinThreshold)
                store.markNotified(ResinReminderStore.HOME_COIN, now)
            }
        }

        reschedule()
    }

    /**
     * 是否还应该为 [resource] 排程。
     *
     * @param plannedAt 计划提醒时刻（重排时可能是未来时刻）
     * @param now 当前时刻
     */
    private suspend fun shouldNotify(
        resource: String,
        config: ReminderConfig,
        plannedAt: Long,
        now: Long
    ): Boolean {
        val last = store.lastNotifiedAt(resource)
        if (last <= 0L) return true
        return if (config.oncePerDay) {
            // 每天一次：今天已经提醒过就不再排（明天自然放开）
            !DailyNoteRepository.isSameLocalDay(last, now)
        } else {
            // 每次达到都提醒：与上次提醒拉开最小间隔，避免同一段数值反复触发
            plannedAt - last >= MIN_REPEAT_INTERVAL_MS
        }
    }

    private fun setAlarm(alarmManager: AlarmManager?, triggerAt: Long) {
        alarmManager ?: return
        val pi = pendingIntent()
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                // 用户未授予精确闹钟权限：降级为非精确（Doze 下可能延后数分钟）
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ResinReminderReceiver::class.java).setAction(ACTION_REMINDER)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** 静态接收器：闹钟到点后由系统拉起，判定并发通知 */
class ResinReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ResinReminderManager.ACTION_REMINDER) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val store = ResinReminderStore(appContext)
        val manager = ResinReminderManager(appContext, store, DailyNoteRepository(appContext))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                manager.onAlarmFired()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** 供 ViewModel 复用的"是不是同一天"判断（与便笺快照同一套口径） */
internal fun sameLocalDay(first: Long, second: Long = System.currentTimeMillis()): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = first }
    val b = Calendar.getInstance().apply { timeInMillis = second }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
