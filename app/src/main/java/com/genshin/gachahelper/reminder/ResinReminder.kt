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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.genshin.gachahelper.ui.home.DailyNoteRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
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
    /**
     * 洞天宝钱每小时产量（个/小时）。
     *
     * 游戏内该值随「洞天仙力」档位变化（0 仙力 4 个/小时 ~ 满仙力 20000 = 30 个/小时），
     * 而每日便笺接口不返回仙力等级，故由用户在设置页按档位选择。
     */
    val homeCoinPerHour: Int = DEFAULT_HOME_COIN_PER_HOUR,
    /** true=每种资源每天最多提醒一次；false=每次达到都提醒（10 分钟防抖） */
    val oncePerDay: Boolean = true
) {
    companion object {
        /** 洞天宝钱上限（接口返回 maxHomeCoin，兜底值） */
        const val DEFAULT_HOME_COIN_MAX = 2400

        /** 树脂上限（接口返回 maxResin，兜底值 200） */
        const val DEFAULT_RESIN_MAX = 200

        /** 洞天宝钱每小时产量默认值：满仙力档位 30 个/小时 */
        const val DEFAULT_HOME_COIN_PER_HOUR = 30

        /** 洞天宝钱产量档位上下限（0 仙力 4 个/小时 ~ 满仙力 30 个/小时） */
        const val MIN_HOME_COIN_PER_HOUR = 4
        const val MAX_HOME_COIN_PER_HOUR = 30

        /** 游戏内洞天仙力对应的全部产量档位，供设置页选择 */
        val HOME_COIN_PER_HOUR_OPTIONS = listOf(4, 8, 12, 16, 20, 22, 24, 26, 28, 30)
    }
}

private val Context.resinReminderStore by preferencesDataStore(name = "resin_reminder_store")

/**
 * 提醒链路自检结果（2026-09-23 新增）。
 *
 * 起因：用户反馈"树脂满了没收到通知"，但通知权限、开关都正常——真实原因是
 * AlarmManager 的一次性闹钟会被"划掉后台 / 电池优化"清掉，而 App 此前对此
 * 完全静默。把这条链路上的每个环节都摊开给用户看，才能分辨是权限、渠道、
 * 系统限制还是"没到点"。
 */
data class ReminderDiagnostics(
    /** 提醒总开关 */
    val enabled: Boolean = false,
    /** Android 13+ 通知运行时权限 */
    val notificationPermissionGranted: Boolean = false,
    /** 提醒渠道未被系统关闭 */
    val channelEnabled: Boolean = true,
    /** 已获得精确闹钟权限（未获得时会降级为不精确闹钟） */
    val exactAlarmAllowed: Boolean = true,
    /** 已加入电池优化白名单（未加入时系统会限制后台闹钟） */
    val batteryOptimizationIgnored: Boolean = false,
    /** 本地是否有便笺快照（无快照则无法推算提醒时刻） */
    val hasSnapshot: Boolean = false,
    /** 快照时间 */
    val snapshotAt: Long = 0L,
    /** 已排入 AlarmManager 的下次提醒时刻，0 表示当前没有排程 */
    val nextTriggerAt: Long = 0L,

    // 2026-09-24 新增：把「为什么没排 / 没响」再摊开一层——
    // 仅靠 nextTriggerAt=0 只能看出"没排"，看不出是"已达标且今天提醒过"还是"阈值还没到"。
    /** 当前树脂阈值（开启提醒时有效） */
    val resinThreshold: Int = 0,
    /** 按快照外推的当前树脂值 */
    val currentResin: Int = 0,
    /** 上次树脂提醒时刻（0=从未提醒） */
    val lastResinNotifiedAt: Long = 0L,
    /** 今天是否已经提醒过树脂 */
    val resinNotifiedToday: Boolean = false,
    /** 是否同时提醒洞天宝钱 */
    val homeCoinEnabled: Boolean = false,
    /** 当前洞天宝钱阈值 */
    val homeCoinThreshold: Int = 0,
    /** 按快照外推的当前洞天宝钱值 */
    val currentHomeCoin: Int = 0,
    /** 上次洞天宝钱提醒时刻（0=从未提醒） */
    val lastHomeCoinNotifiedAt: Long = 0L,
    /** 今天是否已经提醒过洞天宝钱 */
    val homeCoinNotifiedToday: Boolean = false
)

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
        private val KEY_COIN_PER_HOUR = intPreferencesKey("home_coin_per_hour")
        private val KEY_ONCE_PER_DAY = booleanPreferencesKey("once_per_day")
        private val KEY_LAST_RESIN_AT = longPreferencesKey("last_resin_notify_at")
        private val KEY_LAST_COIN_AT = longPreferencesKey("last_coin_notify_at")

        /** 已排入 AlarmManager 的下一次提醒时刻，用于设置页「提醒自检」展示 */
        private val KEY_NEXT_TRIGGER_AT = longPreferencesKey("next_trigger_at")



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
            homeCoinPerHour = (prefs[KEY_COIN_PER_HOUR] ?: defaults.homeCoinPerHour)
                .coerceIn(
                    ReminderConfig.MIN_HOME_COIN_PER_HOUR,
                    ReminderConfig.MAX_HOME_COIN_PER_HOUR
                ),
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
            prefs[KEY_COIN_PER_HOUR] = config.homeCoinPerHour
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

    /** 已排入 AlarmManager 的下一次提醒时刻（毫秒），0 表示当前没有排程 */
    suspend fun nextTriggerAt(): Long =
        context.resinReminderStore.data.first()[KEY_NEXT_TRIGGER_AT] ?: 0L

    suspend fun setNextTriggerAt(at: Long) {
        context.resinReminderStore.edit { it[KEY_NEXT_TRIGGER_AT] = at }
    }

    /**
     * 清除 [resource] 的「已提醒」标记。
     *
     * 去重是按资源（而非按阈值）记账的，阈值/开关一变就必须清一次，
     * 否则会出现"今天按 120 提醒过 → 改成 200 后当天永不提醒"。
     */
    suspend fun clearNotified(resource: String) {
        context.resinReminderStore.edit { prefs -> prefs.remove(lastKey(resource)) }
    }

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
    /** 渠道 ID：设置页「提醒自检」跳转渠道设置时会用到，故对外可见 */
    const val CHANNEL_ID = "resin_reminder"
    private const val RESIN_NOTIFICATION_ID = 2001
    private const val COIN_NOTIFICATION_ID = 2002
    private const val TEST_NOTIFICATION_ID = 2003
    private const val TEST_ALARM_NOTIFICATION_ID = 2004

    /** 测试闹钟通知的去抖窗口：同一次测试闹钟重复到达时只发一条 */
    private const val TEST_ALARM_DEDUPE_MS = 60_000L

    private val testAlarmLock = Any()

    @Volatile
    private var lastTestAlarmNotifyAt = 0L

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

    fun notifyResin(context: Context, current: Int, threshold: Int): Boolean {
        val text = if (current >= threshold) {
            "树脂已达到 $current（阈值 $threshold），去清体力吧"
        } else {
            "树脂已达阈值 $threshold"
        }
        return send(context, RESIN_NOTIFICATION_ID, "树脂提醒", text)
    }

    fun notifyHomeCoin(context: Context, current: Int, threshold: Int): Boolean =
        send(context, COIN_NOTIFICATION_ID, "洞天宝钱提醒", "洞天宝钱已达到 $current（阈值 $threshold），去收钱吧")

    /**
     * 设置页「发送测试通知」：直接把通知链路（权限 → 渠道 → 发送）跑通一次，
     * 让用户/排查者立刻区分"链路坏了"还是"根本没到点"。
     */
    fun sendTest(context: Context): Boolean =
        send(context, TEST_NOTIFICATION_ID, "树脂提醒测试", "看到这条通知说明提醒链路正常（权限、渠道、发送均可用）")

    /**
     * 测试闹钟到点后的反馈通知：明确告诉用户"闹钟链路通了"，并提示如何据此判断清后台是否安全。
     * 与真实提醒完全隔离（不依赖开关/快照/去重），纯粹验证 AlarmManager → 接收器 → 通知 这条链路。
     */
    fun notifyTestAlarm(context: Context): Boolean {
        // 测试闹钟去抖：同一时刻重复到达时只发一条（防御性，当前仅系统闹钟一条路径）。
        // 理由同上：进程活着时两条路都会到点，若不折叠用户会看到两条重复通知。
        val now = System.currentTimeMillis()
        synchronized(testAlarmLock) {
            if (now - lastTestAlarmNotifyAt < TEST_ALARM_DEDUPE_MS) return true
            lastTestAlarmNotifyAt = now
        }
        val text = "✅ 测试闹钟已响：这是 10 秒前排入的闹钟。" +
            "若你刚才清过后台它仍响，说明清后台提醒链路正常；若不响，说明你的 ROM 会强杀闹钟，" +
            "请把本应用加入「自启动 / 电池优化」白名单。"
        return send(context, TEST_ALARM_NOTIFICATION_ID, "树脂提醒·测试闹钟", text)
    }

    /** 通知权限是否已授予（Android 13+ 需运行时授权，未授权时通知会被系统丢弃） */
    fun hasPermission(context: Context): Boolean {
        // Android 12 及以下不存在 POST_NOTIFICATIONS 运行时权限，系统默认允许通知；
        // 若仍按 13+ 口径硬查该权限字符串，会误判为「未授权」导致所有通知静默。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 提醒渠道是否可用：渠道被用户在系统里关闭（importance=NONE）时通知不会显示 */
    fun isChannelEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun send(context: Context, id: Int, title: String, text: String): Boolean {
        if (!hasPermission(context)) return false
        if (!isChannelEnabled(context)) return false
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
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
        const val ACTION_TEST_ALARM = "com.genshin.gachahelper.action.RESIN_REMINDER_TEST"
        private const val REQUEST_CODE = 4101
        private const val REQUEST_CODE_TEST = 4102

        /** 已达标但还没提醒过时，延迟这么久发（给"立即提醒"留出进程启动时间） */
        private const val IMMEDIATE_DELAY_MS = 3_000L

        /** 测试闹钟：排入后多久响（毫秒），默认 10 秒 */
        const val TEST_ALARM_DELAY_MS = 10_000L

        /** oncePerDay=false（每次达到都提醒）场景的最小提醒间隔，防止同值反复触发 */
        private const val MIN_REPEAT_INTERVAL_MS = 10 * 60_000L

        /**
         * 同一次提醒的去抖窗口（防御性）。
         *
         * 过去存在系统闹钟 + Shizuku 守护两个触发源时用于折叠重复通知；如今只剩系统闹钟一条路径，
         * 该窗口保留为防御性去重。1 分钟窗口远小于任何合法的下一次提醒间隔，不会误吞。
         */
        private const val FIRE_DEDUPE_WINDOW_MS = 60_000L
    }

    /** 配合 [FIRE_DEDUPE_WINDOW_MS]，同窗口内只允许处理一次，避免重复通知 */
    private val fireLock = Any()

    @Volatile
    private var lastFireHandledAt = 0L

    /**
     * 保存配置并按新阈值重排闹钟：设置页与便笺卡片共用同一入口，
     * 避免两处各写一套"落库 + 排程"逻辑。
     */
    suspend fun update(transform: (ReminderConfig) -> ReminderConfig) {
        val before = store.current()
        val after = transform(before)
        store.save(after)
        // 去重是按「资源」而非「阈值」记账的，阈值/开关一变就必须清掉当日标记：
        // 否则会出现"今天按 120 提醒过，改成 200 后当天永不提醒"——用户视角就是"满了不响"。
        if (after.resinThreshold != before.resinThreshold || (!before.enabled && after.enabled)) {
            store.clearNotified(ResinReminderStore.RESIN)
        }
        if (after.homeCoinThreshold != before.homeCoinThreshold ||
            after.homeCoinEnabled != before.homeCoinEnabled
        ) {
            store.clearNotified(ResinReminderStore.HOME_COIN)
        }
        reschedule()
    }

    /**
     * 便笺快照更新 / 配置变更 / 开机 / 进程重启后调用：按最新锚点重排下一次提醒。
     *
     * ⚠️ 2026-10-08 关键修正（对应「后台一清、重开 App 就失效」）：
     * 旧实现在**开头无条件 [cancel]** —— 而本方法在 App 每次冷启动（[com.genshin.gachahelper.GachaApp.onCreate]）
     * 都会跑一遍。冷启动那一瞬间，快照尚未加载 / 已被清掉时 `loadLatest()` 返回 null，
     * 旧代码于是"先清空系统闹钟，再直接 return" —— 结果是**重开 App 反而把
     * 已经守好的提醒清成了 0**。用户视角就是"清后台后重新打开，提醒就没了"。
     *
     * 现在的原则：**只有在确实算出新的（或明确不需要）排程时才动闹钟**。
     * 算不出来就原样保留现状，绝不空手清场。
     * [onAlarmFired] 到点会重新判定阈值与去重，不达标的不会发通知。
     */
    suspend fun reschedule() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val config = store.current()

        // 提醒被关掉：明确解除武装（取消系统闹钟），避免留下到点仍响的幽灵通知
        if (!config.enabled) {
            cancel()
            return
        }

        // 没有快照 → 无从推算。**保留现状**，等拿到快照后由下一次 reschedule 接管。
        // （旧实现在这里直接清空排程，是"重开 App 就失效"的直接原因之一。）
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

        val next = candidates.minOrNull()
        if (next == null) {
            // 已达标且今日已提醒 / 无任何达标候选 → 此时才解除武装
            cancel()
            return
        }
        val triggerAt = if (next <= now) now + IMMEDIATE_DELAY_MS else next
        store.setNextTriggerAt(triggerAt)
        // setAlarm/setAlarmClock 用同一个 PendingIntent（REQUEST_CODE 固定），会直接覆盖上一条，
        // 所以这里不再需要"先 cancel 再 set"，也就不会再出现"清了却没能重新排上"的空窗。
        setAlarm(alarmManager, triggerAt)
    }

    suspend fun cancel() {
        store.setNextTriggerAt(0L)
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent())
    }



    /**
     * 提醒链路自检：把"为什么不响"逐环节摊开（权限 / 渠道 / 精确闹钟 / 电池优化 / 快照 / 已排时刻）。
     *
     * 注意 [nextTriggerAt] 只反映"本应用最后一次排程的结果"——若此后系统因
     * force-stop / 电池优化清掉了闹钟，这里看不出来，需要靠"实际没响"来暴露。
     */
    suspend fun diagnostics(): ReminderDiagnostics {
        // 2026-09-23 性能：检测要读 DataStore 快照 + 跨进程查通知/闹钟/电池优化状态，
        // 全部挪到 IO 线程，避免在设置页拖动滑杆时占住主线程。
        return withContext(Dispatchers.IO) {
            val config = store.current()
            val note = dailyNoteRepository.loadLatest()
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val powerManager = context.getSystemService(PowerManager::class.java)
            val now = System.currentTimeMillis()
            val lastResin = store.lastNotifiedAt(ResinReminderStore.RESIN)
            val lastCoin = store.lastNotifiedAt(ResinReminderStore.HOME_COIN)
            ReminderDiagnostics(
                enabled = config.enabled,
                notificationPermissionGranted = ResinReminderNotifier.hasPermission(context),
                channelEnabled = ResinReminderNotifier.isChannelEnabled(context),
                exactAlarmAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    (alarmManager?.canScheduleExactAlarms() ?: false),
                batteryOptimizationIgnored =
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
                hasSnapshot = note != null,
                snapshotAt = note?.fetchedAt ?: 0L,
                nextTriggerAt = store.nextTriggerAt(),
                resinThreshold = config.resinThreshold,
                currentResin = note?.resinAt(now)?.value ?: 0,
                lastResinNotifiedAt = lastResin,
                resinNotifiedToday = DailyNoteRepository.isSameLocalDay(lastResin, now),
                homeCoinEnabled = config.homeCoinEnabled,
                homeCoinThreshold = config.homeCoinThreshold,
                currentHomeCoin = note?.homeCoinAt(now)?.value ?: 0,
                lastHomeCoinNotifiedAt = lastCoin,
                homeCoinNotifiedToday = DailyNoteRepository.isSameLocalDay(lastCoin, now)
            )
        }
    }

    /** 设置页「发送测试通知」：仅验证通知链路，不改动提醒状态与去重标记 */
    fun sendTestNotification(): Boolean {
        ResinReminderNotifier.ensureChannel(context)
        return ResinReminderNotifier.sendTest(context)
    }

    /**
     * 闹钟到点：判定是否真的达标并通知，然后按新锚点重排。
     *
         * 闹钟到点由系统闹钟 AlarmManager → [ResinReminderReceiver] 拉起（进程活着时）。
         * 入口处保留短窗口去抖作防御性去重，避免同一时刻重复通知。
     */
    suspend fun onAlarmFired() {
        val now0 = System.currentTimeMillis()
        // 去抖必须「判定 + 记录」原子完成：同一时刻重复到达时若各自通过检查，仍会重复发通知。
        synchronized(fireLock) {
            if (now0 - lastFireHandledAt < FIRE_DEDUPE_WINDOW_MS) return
            lastFireHandledAt = now0
        }

        val config = store.current()
        if (!config.enabled) return
        val note = dailyNoteRepository.loadLatest()
        val now = System.currentTimeMillis()

        // 2026-09-24 修复：notifyXxx 的返回值表示"是否真的发出去了"（权限/渠道任一不通即 false）。
        // 此前忽略返回值、无条件 markNotified，导致"通知没发出、却被记为今天已提醒"，当天再不排程
        // ——用户视角就是"树脂满了却永远收不到提醒"。必须只在发送成功后才记账。
        if (note != null) {
            val resinProjection = note.resinAt(now)
            if (shouldNotify(ResinReminderStore.RESIN, config, now, now) &&
                resinProjection.value >= config.resinThreshold
            ) {
                val sent = ResinReminderNotifier.notifyResin(
                    context, resinProjection.value, config.resinThreshold
                )
                if (sent) store.markNotified(ResinReminderStore.RESIN, now)
            }

            if (config.homeCoinEnabled) {
                val coinProjection = note.homeCoinAt(now)
                if (shouldNotify(ResinReminderStore.HOME_COIN, config, now, now) &&
                    coinProjection.value >= config.homeCoinThreshold
                ) {
                    val sent = ResinReminderNotifier.notifyHomeCoin(
                        context, coinProjection.value, config.homeCoinThreshold
                    )
                    if (sent) store.markNotified(ResinReminderStore.HOME_COIN, now)
                }
            }
        }

        // 通知链路不可用（权限未授予 / 渠道被关闭）时，本资源仍然"已达标且未记账"，
        // 若照常重排会得到 "now + 3s" 的触发点，形成每 3 秒一次、永远发不出去的静默死循环。
        // 此时清空排程，交由「提醒自检」面板把问题暴露给用户；用户修好权限后重开开关即可重排。
        // 注意：即便快照缺失也走到这里继续重排——让闹钟在"无快照"时也能自愈，而非永久断链。
        if (!ResinReminderNotifier.hasPermission(context) ||
            !ResinReminderNotifier.isChannelEnabled(context)
        ) {
            store.setNextTriggerAt(0L)
            return
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
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            // 优先用 setAlarmClock：这是唯一能抵御国产 ROM「清后台/强杀即清闹钟」的闹钟类型，
            // 系统会把它当作状态栏时钟对待，不会被后台管理静默丢弃
            // （代价是状态栏会显示一个"到点提醒"的小闹钟图标，点到时即消失）。
            val info = AlarmManager.AlarmClockInfo(triggerAt, pi)
            alarmManager.setAlarmClock(info, pi)
        } catch (_: SecurityException) {
            // 极端兜底：降级为精确闹钟 → 非精确 → 普通闹钟
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

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ResinReminderReceiver::class.java).setAction(ACTION_REMINDER)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 排入一个 [TEST_ALARM_DELAY_MS] 毫秒后响的测试闹钟（默认 10 秒）。
     *
     * 用途：验证「清后台 / 锁屏 / 划掉 App」后提醒链路是否真能响——用户实测
     * 「10 秒测试闹钟被清后台后一样没有」，验证的正是这条链路。
     *
         * 系统闹钟会被 ROM 的清后台动作连同进程一起抹掉，这就是为什么「清后台后测试不响」——此时请按设置页「抗清后台必做」完成系统设置三件套。
     *
     * 与真实提醒一样：**不读取 / 不修改任何提醒状态**（不改开关、不动去重标记、不重排真实闹钟）。
     *
     * @return 排程的触发时刻（毫秒），0 表示排入失败
     */
    fun scheduleTestAlarm(): Long {
        val triggerAt = System.currentTimeMillis() + TEST_ALARM_DELAY_MS
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        // ① 系统闹钟：始终排。这是 1.9.19 起早已验证可行的一条路，也是「进程活着时」
        //    唯一能保证到点就响的机制。
        val viaAlarmManager: Boolean = if (alarmManager != null) {
            val pi = pendingIntentTest()
            try {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
                true
            } catch (_: SecurityException) {
                try {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        alarmManager.canScheduleExactAlarms()
                    ) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                    true
                } catch (_: SecurityException) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    true
                }
            }
        } else {
            false
        }


        return if (viaAlarmManager) triggerAt else 0L
    }


    private fun pendingIntentTest(): PendingIntent {
        val intent = Intent(context, ResinReminderTestReceiver::class.java)
            .setAction(ACTION_TEST_ALARM)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_TEST,
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

/**
 * 测试闹钟接收器：仅供设置页「10 秒后测试闹钟」按钮使用。
 *
 * 与 [ResinReminderReceiver] 完全隔离——不读开关、不读快照、不动去重标记、不重排真实闹钟，
 * 只发一条"测试闹钟已响"通知，用于验证「清后台 / 锁屏下 setAlarmClock 是否真能拉起通知」。
 */
class ResinReminderTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ResinReminderManager.ACTION_TEST_ALARM) return
        val appContext = context.applicationContext
        ResinReminderNotifier.ensureChannel(appContext)
        ResinReminderNotifier.notifyTestAlarm(appContext)
    }
}

/** 供 ViewModel 复用的"是不是同一天"判断（与便笺快照同一套口径） */
internal fun sameLocalDay(first: Long, second: Long = System.currentTimeMillis()): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = first }
    val b = Calendar.getInstance().apply { timeInMillis = second }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
