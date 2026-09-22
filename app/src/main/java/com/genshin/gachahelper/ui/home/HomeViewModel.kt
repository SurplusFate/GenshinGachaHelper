package com.genshin.gachahelper.ui.home

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.analysis.GachaReport
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.analysis.PoolStats
import com.genshin.gachahelper.auth.ApiResult
import com.genshin.gachahelper.auth.AppLog
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.auth.CaptchaData
import com.genshin.gachahelper.auth.DailyNoteData
import com.genshin.gachahelper.auth.DailyNoteService
import com.genshin.gachahelper.auth.GeetestResult
import com.genshin.gachahelper.auth.VerificationService
import com.genshin.gachahelper.auth.asStringSafe
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.reminder.ReminderConfig
import com.genshin.gachahelper.reminder.ResinReminderManager
import com.genshin.gachahelper.reminder.ResinReminderStore
import com.genshin.gachahelper.sync.GachaSyncService
import com.genshin.gachahelper.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val uid: String? = null,
    val nickname: String? = null,
    val hasData: Boolean = false,
    val characterStats: PoolStats? = null,
    val character2Stats: PoolStats? = null,
    val weaponStats: PoolStats? = null,
    val standardStats: PoolStats? = null,
    val noviceStats: PoolStats? = null,
    val chronicledStats: PoolStats? = null,
    val recentFiveStars: List<GachaRecordEntity> = emptyList(),
    val recentFiveStarIntervals: List<Int> = emptyList(),
    /** 全局抽卡报告（由计算引擎生成，UI 禁止自行计算平均出金等指标） */
    val report: GachaReport? = null,
    val syncState: SyncState = SyncState.Idle,
    val isLoading: Boolean = true,
    /** 每日便笺（树脂 / 洞天宝钱）实时状态，未登录或拉取失败时为 null */
    val dailyNote: DailyNoteData? = null,
    val dailyNoteLoading: Boolean = false,
    val dailyNoteError: String? = null,
    /** 便笺错误码：1034 = 触发风控验证，卡片显示「完成安全验证」入口 */
    val dailyNoteErrorCode: Int? = null,
    /** geetest 风控验证参数：非 null 时弹出滑块验证对话框 */
    val captcha: CaptchaData? = null
    // 2026-09-20：logExportDialog 字段已随日志导出功能迁至 SettingsViewModel
    // （入口：设置 → 关于 → 导出日志）。
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val gachaRepository: GachaRepository,
    private val statsCalculator: GachaStatsCalculator,
    private val syncService: GachaSyncService,
    private val dailyNoteService: DailyNoteService,
    /** 便笺快照本地持久化：每天只在首次启动拉一次网络，其余时间读本地快照推算 */
    private val dailyNoteRepository: DailyNoteRepository,
    private val verificationService: VerificationService,
    private val sessionEventBus: SessionEventBus,
    // 2026-09-22：树脂/洞天宝钱阈值提醒（规则主体在设置页，卡片只放快捷入口）
    private val reminderStore: ResinReminderStore,
    private val reminderManager: ResinReminderManager,
    // 2026-09-20：米游社签到入口从设置页迁到便笺页（用户需求"把自动签到放到便笺来"）
    private val signInRepository: com.genshin.gachahelper.signin.SignInRepository,
    // 极验预检用：verifyVerification 失败时直接调 validate.php 裁决 validate 真伪
    private val okHttpClient: okhttp3.OkHttpClient
) : ViewModel() {

    companion object {
        /**
         * 每日便笺重复请求保护窗口：接口有频率限制，60 秒内不重复发起网络请求。
         *
         * 常态展示不依赖本窗口——便笺每个自然日只在首次启动时同步一次快照，
         * 其余时间由 [DailyNoteData.resinAt] / [DailyNoteData.homeCoinAt]
         * 基于快照与本地时钟外推（详见 [loadDailyNote]）。
         */
        private const val DAILY_NOTE_TTL_MS = 60_000L

        /** 跨天巡检间隔：进程常驻跨过 0 点时也能补上当日同步 */
        private const val DAILY_NOTE_DAY_WATCH_MS = 5 * 60_000L

        /** 1034 自动弹验证的次数上限：防止 token 异常时的弹窗循环，超出后仅手动触发 */
        private const val MAX_AUTO_CAPTCHA = 2

        /** 极验预检结果（2026-09 定罪修复）：假验证 / 真验证 / 预检接口不可达 */
        private const val GEETEST_VALID = 1
        private const val GEETEST_INVALID = 0
        private const val GEETEST_UNKNOWN = -1
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ------------------------------------------------------------------
    // 米游社每日自动签到（2026-09-20 入口迁至便笺页，逻辑原属设置页）
    // ------------------------------------------------------------------

    /** 每日自动签到开关 */
    val dailySignEnabled: StateFlow<Boolean> =
        signInRepository.enabledFlow.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    /** 最近一次签到结果提示 */
    val dailySignResult: StateFlow<String?> = signInRepository.lastResult.asStateFlow()

    /** 开关每日自动签到 */
    fun setDailySignEnabled(enabled: Boolean) {
        signInRepository.setEnabled(enabled)
    }

    /** 手动立即签到一次（不影响已排队的自动任务） */
    fun manualDailySignIn() {
        viewModelScope.launch {
            signInRepository.performManualSignIn()
        }
    }

    // ------------------------------------------------------------------
    // 树脂 / 洞天宝钱阈值提醒（2026-09-22 新增）
    // ------------------------------------------------------------------

    /** 提醒配置：便笺卡片的快捷入口与设置页读写同一份持久化数据 */
    val reminderConfig: StateFlow<ReminderConfig> = reminderStore.configFlow.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        initialValue = ReminderConfig()
    )

    fun setReminderEnabled(enabled: Boolean) = updateReminder { it.copy(enabled = enabled) }

    fun setResinThreshold(threshold: Int) = updateReminder {
        it.copy(resinThreshold = threshold.coerceIn(1, ReminderConfig.DEFAULT_RESIN_MAX))
    }

    fun setHomeCoinEnabled(enabled: Boolean) = updateReminder { it.copy(homeCoinEnabled = enabled) }

    fun setHomeCoinThreshold(threshold: Int) = updateReminder {
        it.copy(homeCoinThreshold = threshold.coerceIn(1, ReminderConfig.DEFAULT_HOME_COIN_MAX))
    }

    fun setReminderOncePerDay(oncePerDay: Boolean) = updateReminder { it.copy(oncePerDay = oncePerDay) }

    /** 落库 + 立即重排闹钟（统一走 manager，避免两处逻辑不一致） */
    private fun updateReminder(transform: (ReminderConfig) -> ReminderConfig) {
        viewModelScope.launch { reminderManager.update(transform) }
    }

    // 串行化 loadData，避免事件并发触发时多个加载重叠写 _uiState 造成 last-write-wins 回退
    private val loadMutex = Mutex()

    // 上次便笺请求时刻，用于节流（接口有频率限制）
    private var lastDailyNoteFetchAt = 0L

    init {
        // 监听同步状态：仅用于 UI 显示同步进度，刷新由 SessionEventBus.DataSynced 驱动
        viewModelScope.launch {
            syncService.syncState.collect { syncState ->
                _uiState.value = _uiState.value.copy(syncState = syncState)
            }
        }

        // 监听全局会话事件（登录/导入/同步/清除等）
        viewModelScope.launch {
            sessionEventBus.events.collect { event ->
                when (event) {
                    SessionEvent.LoginCompleted,
                    SessionEvent.DataImported,
                    SessionEvent.DataSynced -> loadData()

                    SessionEvent.LogoutCompleted,
                    SessionEvent.DataCleared -> {
                        _uiState.value = HomeUiState(isLoading = false)
                        // 退出登录后立即失效便笺节流窗口，并清掉本地快照，
                        // 避免换号 / 退出后仍按上一个账号的数据继续本地推算
                        lastDailyNoteFetchAt = 0L
                        dailyNoteRepository.clear()
                        // 清掉挂在旧账号快照上的提醒闹钟，避免退出后仍弹树脂提醒
                        reminderManager.cancel()
                    }
                }
            }
        }

        loadData()

        // 洞天宝钱产量档位变化时，立即按新档位刷新便笺推算
        // （接口不返回洞天仙力等级，档位由设置页选择，与提醒配置共用一份 DataStore）
        viewModelScope.launch {
            reminderStore.configFlow.collect { config ->
                val note = _uiState.value.dailyNote ?: return@collect
                if (note.homeCoinPerHour == config.homeCoinPerHour) return@collect
                _uiState.value = _uiState.value.copy(
                    dailyNote = note.copy(homeCoinPerHour = config.homeCoinPerHour)
                )
                reminderManager.reschedule()
            }
        }

        // 跨天巡检：进程常驻（长期不冷启动）跨过 0 点后，补一次当日同步。
        // 仅在"本地快照非今日"时才会真正发起网络请求，其余周期只读一次本地快照。
        viewModelScope.launch {
            while (true) {
                delay(DAILY_NOTE_DAY_WATCH_MS)
                val uid = authRepository.getUid()
                if (!authRepository.isLoggedIn() || uid.isNullOrBlank()) continue
                val cached = dailyNoteRepository.load(uid)
                if (cached == null || !DailyNoteRepository.isSameLocalDay(cached.fetchedAt)) {
                    loadDailyNote(force = false)
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            loadMutex.withLock {
                // 已有数据时静默刷新：不把 isLoading 置 true，避免每次切回 tab /
                // 事件刷新时整页先闪 loading 再重绘，造成"卡顿/闪屏"的观感。
                val snapshot = _uiState.value
                if (snapshot.report == null && snapshot.characterStats == null) {
                    _uiState.value = snapshot.copy(isLoading = true)
                }
                val loggedIn = authRepository.isLoggedIn()
                val authUid = authRepository.getUid()
                val nickname = authRepository.getNickname()

                // 通过活跃账号解析：登录时用登录 UID，未登录时回退到最近导入的账号
                val account = gachaRepository.getActiveAccount(authUid)

                // UID 显示优先级：
                // 1. 已登录 → 登录 UID（不依赖 AccountEntity）
                // 2. 未登录但有本地数据 → 本地数据 UID
                // 3. 未登录且无数据 → null（UI 显示"未绑定"）
                val displayUid = when {
                    loggedIn -> authUid
                    account != null -> account.uid
                    else -> null
                }

                _uiState.value = _uiState.value.copy(
                    isLoggedIn = loggedIn,
                    uid = displayUid,
                    nickname = nickname
                )

                if (account != null) {
                    loadStats(account.id)
                } else {
                    _uiState.value = _uiState.value.copy(
                        hasData = false,
                        isLoading = false
                    )
                }

                // 便笺（树脂/洞天宝钱）：登录态确认后再调用。内部会判断本地快照是否为
                // "今天"——是则纯本地读取（零网络），否则同步一次快照。
                // 缺了这一步会导致首次进入首页 dailyNote/dailyNoteError 均为 null，卡片永不展示。
                loadDailyNote(force = false)
            }
        }
    }

    /**
     * 一次性生成全量统计报告（含各池 PoolStats）的临时装载器。
     * 用于避免 [statsCalculator] 的重复全量计算全部叠加在主线程上。
     */
    private data class StatsLoadResult(
        val report: GachaReport?,
        val recentFiveStars: List<GachaRecordEntity>,
        val recentFiveStarIntervals: List<Int>,
        val hasData: Boolean
    )

    private suspend fun loadStats(accountId: Long) {
        // DB 拉取与全量统计均在后台线程执行，避免数万条记录排序/分组阻塞主线程。
        val result = withContext(Dispatchers.Default) {
            // 加载各卡池记录并计算统计（包含角色活动祈愿-2 和集录祈愿）
            val characterRecords = gachaRepository.getRecordsByPool(
                accountId, GachaType.CHARACTER.value
            )
            val character2Records = gachaRepository.getRecordsByPool(
                accountId, GachaType.CHARACTER_2.value
            )
            val weaponRecords = gachaRepository.getRecordsByPool(
                accountId, GachaType.WEAPON.value
            )
            val standardRecords = gachaRepository.getRecordsByPool(
                accountId, GachaType.STANDARD.value
            )
            val chronicledRecords = gachaRepository.getRecordsByPool(
                accountId, GachaType.CHRONICLED.value
            )
            val noviceRecords = gachaRepository.getRecordsByPool(
                accountId, GachaType.NOVICE.value
            )

            // 生成全局报告（内部一次性算出各池 PoolStats，UI 层禁止重复调用计算器）
            val report = statsCalculator.generateReport(
                characterRecords = characterRecords,
                character2Records = character2Records,
                weaponRecords = weaponRecords,
                standardRecords = standardRecords,
                noviceRecords = noviceRecords,
                chronicledRecords = chronicledRecords
            )

            val hasAnyData = report.totalPulls > 0

            // 加载最近五星记录（最多 10 条），并计算每条五星距上一个五星的出金间隔
            val poolRecords = mapOf(
                GachaType.CHARACTER.value to characterRecords,
                GachaType.CHARACTER_2.value to character2Records,
                GachaType.WEAPON.value to weaponRecords,
                GachaType.STANDARD.value to standardRecords,
                GachaType.NOVICE.value to noviceRecords,
                GachaType.CHRONICLED.value to chronicledRecords
            )
            val recentWithIntervals = loadRecentFiveStars(accountId, poolRecords)

            StatsLoadResult(
                report = report,
                recentFiveStars = recentWithIntervals.map { it.first },
                recentFiveStarIntervals = recentWithIntervals.map { it.second },
                hasData = hasAnyData
            )
        }

        _uiState.value = _uiState.value.copy(
            characterStats = result.report?.characterPoolStats,
            character2Stats = result.report?.character2PoolStats,
            weaponStats = result.report?.weaponPoolStats,
            standardStats = result.report?.standardPoolStats,
            noviceStats = result.report?.novicePoolStats,
            chronicledStats = result.report?.chronicledPoolStats,
            recentFiveStars = result.recentFiveStars,
            recentFiveStarIntervals = result.recentFiveStarIntervals,
            report = result.report,
            hasData = result.hasData,
            isLoading = false
        )
    }

    /**
     * 加载最近五星记录（最多 10 条），并计算每条五星的出金间隔。
     *
     * 间隔计算委托给 GachaStatsCalculator：
     * 角色池 301+400 共享保底，合并后按 orderNumber 计算间隔；其他池单独算。
     */
    private suspend fun loadRecentFiveStars(
        accountId: Long,
        poolRecords: Map<Int, List<GachaRecordEntity>>
    ): List<Pair<GachaRecordEntity, Int>> {
        val allFiveStars = gachaRepository.getAllFiveStars(accountId)
        val recent = allFiveStars.take(10)
        if (recent.isEmpty()) return emptyList()

        // 五星记录 id -> 出金间隔（距上一个五星多少抽）
        val intervalById = mutableMapOf<Long, Int>()

        // 角色池 301+400 合并计算（共享保底）
        val charRecords = (poolRecords[GachaType.CHARACTER.value].orEmpty() +
            poolRecords[GachaType.CHARACTER_2.value].orEmpty())
        computeIntervalsForPool(charRecords, intervalById)

        // 其他池单独计算
        for ((type, records) in poolRecords) {
            if (type == GachaType.CHARACTER.value || type == GachaType.CHARACTER_2.value) continue
            computeIntervalsForPool(records, intervalById)
        }

        return recent.map { fiveStar ->
            fiveStar to (intervalById[fiveStar.id] ?: 0)
        }
    }

    /**
     * 计算一组记录中每条五星的出金间隔，结果写入 [result] Map。
     * 使用 GachaStatsCalculator 计算间隔列表，再与五星记录一一对应。
     */
    private fun computeIntervalsForPool(
        records: List<GachaRecordEntity>,
        result: MutableMap<Long, Int>
    ) {
        if (records.isEmpty()) return
        // 用计算器获取按 orderNumber 排序后的间隔列表
        val intervals = statsCalculator.calculateFiveStarIntervals(records)
        // 获取按 orderNumber 升序排列的五星记录
        val fiveStarsInOrder = records
            .filter { it.rarity == 5 }
            .sortedBy { it.orderNumber }
        // 一一对应
        for (i in fiveStarsInOrder.indices) {
            if (i < intervals.size) {
                result[fiveStarsInOrder[i].id] = intervals[i]
            }
        }
    }

    fun sync() {
        viewModelScope.launch {
            syncService.syncAll()
        }
    }

    /**
     * 刷新每日便笺（树脂 / 洞天宝钱）。
     *
     * @param force 用户主动点击刷新时传 true，跳过"当天已同步"判断与节流窗口，
     *              强制走一次网络请求；页面自动刷新走默认的本地优先路径。
     */
    fun refreshDailyNote(force: Boolean = false) {
        viewModelScope.launch { loadDailyNote(force) }
    }

    /**
     * 每日便笺加载：每个自然日只在首次启动时同步一次快照，其余时间纯本地推算。
     *
     * 流程：
     * 1. 读取本地快照（按 UID 隔离，换号不会串数据）；
     * 2. 快照属于"今天" → 直接灌入 UI，零网络请求。之后数值的增长与倒计时由
     *    [DailyNoteData.resinAt] / [DailyNoteData.homeCoinAt] 按固定恢复速率
     *    本地外推（树脂 8 分钟/点连续恢复、洞天宝钱每小时批量 +30 个），每秒刷新一次即可；
     * 3. 快照缺失或已跨天 → 走一次网络同步并落盘，作为当天推算的新锚点；
     * 4. 网络失败 → 保留上次成功数据 / 展示错误与验证入口，不静默清空卡片。
     *
     * @param force 用户手动刷新、或 1034 验证成功后的强刷：忽略"当天已同步"判断。
     */
    private suspend fun loadDailyNote(force: Boolean) {
        // 未登录没有有效 Cookie，接口必然失败，直接跳过（首页此时不会展示该卡片）
        if (!authRepository.isLoggedIn()) return
        val uid = authRepository.getUid()?.takeIf { it.isNotBlank() } ?: return

        val now = System.currentTimeMillis()
        // 洞天宝钱产量档位随设置走：旧快照落盘时没有该字段，这里统一覆盖
        val homeCoinPerHour = reminderStore.current().homeCoinPerHour
        val cached = dailyNoteRepository.load(uid)?.copy(homeCoinPerHour = homeCoinPerHour)
        val cachedIsToday = cached != null &&
            DailyNoteRepository.isSameLocalDay(cached.fetchedAt, now)

        // 本地已有"今天"的快照：只更新 UI，不触碰网络——这就是"每天只拉一次"的落点
        if (!force && cachedIsToday) {
            lastDailyNoteFetchAt = now
            _uiState.value = _uiState.value.copy(
                dailyNote = cached,
                dailyNoteError = null,
                dailyNoteErrorCode = null,
                dailyNoteLoading = false
            )
            // 每次拿到（或读到）快照都按最新锚点重排阈值提醒的闹钟，
            // 保证"预计到达时刻"跟着最新数据走
            reminderManager.reschedule()
            return
        }

        // 需要联网时的重复请求保护：跨天补拉失败后不至于高频重试
        if (!force && now - lastDailyNoteFetchAt < DAILY_NOTE_TTL_MS) return
        lastDailyNoteFetchAt = now
        _uiState.value = _uiState.value.copy(dailyNoteLoading = true)

        when (val result = dailyNoteService.fetchDailyNote()) {
            is ApiResult.Success -> {
                // 落盘作为当天推算的锚点：本次启动之后的数值增长全部由本地外推
                val fresh = result.data.copy(homeCoinPerHour = homeCoinPerHour)
                dailyNoteRepository.save(uid, fresh)
                // 新快照落地后按最新锚点重排阈值提醒
                reminderManager.reschedule()
                _uiState.value = _uiState.value.copy(
                    dailyNote = fresh,
                    dailyNoteError = null,
                    dailyNoteErrorCode = null,
                    dailyNoteLoading = false
                )
            }

            is ApiResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    // 保留上一次成功的数据，仅在无数据时才展示错误，避免刷新失败导致卡片消失
                    dailyNoteError = result.message,
                    dailyNoteErrorCode = result.code,
                    dailyNoteLoading = false
                )
                // 1034（风控验证）：自动发起验证流程；限制次数防止 token 异常时的弹窗循环
                if (result.code == 1034 &&
                    _uiState.value.captcha == null &&
                    autoCaptchaCount < MAX_AUTO_CAPTCHA
                ) {
                    autoCaptchaCount++
                    startCaptchaVerification()
                }
            }
        }
    }

    // ==================== 1034 风控验证流程 ====================

    /** 1034 自动弹验证计数（用户主动完成验证后重置） */
    private var autoCaptchaCount = 0

    /**
     * 发起安全验证：申请 geetest 滑块参数，成功后 UI 弹出验证对话框。
     * 由便笺 1034 自动触发，或用户点击卡片上的「完成安全验证」手动触发。
     */
    fun startCaptchaVerification() {
        viewModelScope.launch {
            com.genshin.gachahelper.auth.AppLog.i(
                "Home", "startCaptchaVerification",
                "用户/自动触发验证流程 auto_count=$autoCaptchaCount"
            )
            when (val result = verificationService.createVerification()) {
                is ApiResult.Success -> {
                    com.genshin.gachahelper.auth.AppLog.i(
                        "Home", "startCaptchaVerification",
                        "createVerification 成功 gt=${result.data.gt} " +
                            "challenge=${result.data.challenge.take(20)}... " +
                            "newCaptcha=${result.data.newCaptcha}"
                    )
                    _uiState.value = _uiState.value.copy(
                        captcha = result.data,
                        dailyNoteError = _uiState.value.dailyNoteError ?: "等待完成安全验证…",
                        dailyNoteLoading = false
                    )
                }

                is ApiResult.Error -> {
                    com.genshin.gachahelper.auth.AppLog.e(
                        "Home", "startCaptchaVerification",
                        "createVerification 失败 msg=${result.message} " +
                            "code=${result.code} step=${result.step} " +
                            "raw=${result.rawResponse.take(220)}"
                    )
                    _uiState.value = _uiState.value.copy(
                        dailyNoteError = "获取安全验证失败：${result.message}",
                        dailyNoteLoading = false
                    )
                }
            }
        }
    }

    /**
     * 用户完成验证后回调：提交验证结果换取一次性放行令牌，
     * 成功后立即强制刷新便笺（此时请求会自动携带 x-rpc-chellange）。
     *
     * 传入的是 WebView 中 getValidate() 的原始 JSON，由
     * [GeetestResult] 自动识别 v3（滑块）/ v4（点选）字段并构造提交体。
     */
    fun onCaptchaSolved(validateJson: String) {
        viewModelScope.launch {
            com.genshin.gachahelper.auth.AppLog.i(
                "Home", "onCaptchaSolved",
                "用户完成验证，原始 validateJson=${validateJson.take(300)}"
            )
            // 先保存 gt（极验预检要用），再关弹窗
            val gtForPrecheck = _uiState.value.captcha?.gt
            // 先关弹窗，避免验证期间用户重复操作
            _uiState.value = _uiState.value.copy(captcha = null)
            val result = runCatching { GeetestResult(JsonParser.parseString(validateJson).asJsonObject) }
                .getOrElse {
                    com.genshin.gachahelper.auth.AppLog.e(
                        "Home", "onCaptchaSolved",
                        "validateJson 解析失败: ${validateJson.take(200)}"
                    )
                    _uiState.value = _uiState.value.copy(
                        dailyNoteError = "验证结果解析失败：${validateJson.take(200)}",
                        dailyNoteErrorCode = -1,
                        dailyNoteLoading = false
                    )
                    return@launch
                }

            when (val r = verificationService.verifyVerification(result)) {
                is ApiResult.Success -> {
                    com.genshin.gachahelper.auth.AppLog.i(
                        "Home", "onCaptchaSolved",
                        "verifyVerification 成功，开始重试 dailyNote"
                    )
                    autoCaptchaCount = 0
                    // 令牌已缓存，跳过节流立即刷新
                    loadDailyNote(force = true)
                }

                is ApiResult.Error -> {
                    com.genshin.gachahelper.auth.AppLog.e(
                        "Home", "onCaptchaSolved",
                        "verifyVerification 失败 code=${r.code} step=${r.step} " +
                            "msg=${r.message} raw=${r.rawResponse.take(220)}"
                    )
                    // ── 极验预检（2026-09 定罪修复）─────────────────────
                    // 用户日志实锤：滑块完成后极验二次校验返回 seccode:false，
                    // 即 WebView 环境里 gt.js 降级产出**本地假 validate**，
                    // 米哈游提交极验必然 -1 空 message。
                    // 这里直接调极验 validate.php 让极验服务端裁决真伪，
                    // 把"假验证"和"米哈游风控拒绝"两种失败区分开。
                    val precheck = precheckGeetest(gtForPrecheck, result)
                    val precheckNote = when (precheck) {
                        GEETEST_INVALID ->
                            "\n【极验裁决：本次验证为伪造/降级产物】\n" +
                                "WebView 环境被极验识别，请关闭弹窗后重试"
                        GEETEST_VALID ->
                            "\n【极验裁决：验证真实有效】\n" +
                                "失败源于米哈游侧（风控/IP/设备信誉）"
                        else -> ""
                    }
                    // 验证参数/令牌均为一次性，失败后必须重新申请，
                    // 否则用户点「完成安全验证」会拿旧参数反复失败。
                    // 诊断信息包含：验证版本、WebView 回传的原始字段、服务端响应
                    val raw = r.rawResponse
                        .take(200)
                        .ifBlank { "（无响应体）" }
                    val ver = if (result.isV4) "v4" else "v3"
                    // seccode 与 validate 不一致时提示后缀已补全（老版 gt.js 不带 |jordan）
                    val secNote = if (result.isV4) "" else {
                        val sec = result.raw.get("geetest_seccode")?.asStringSafe()
                        val vali = result.raw.get("geetest_validate")?.asStringSafe()
                        when {
                            sec == null -> "\nseccode=缺失"
                            sec == vali -> "\nseccode=与validate相同（提交时已补 |jordan）"
                            "|" in sec -> "\nseccode=已含后缀"
                            else -> "\nseccode=其他格式"
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        dailyNoteError = "安全验证失败($ver)：${r.message}\n" +
                            "字段=${validateJson.take(200)}$secNote\n" +
                            "阶段=${r.step} 响应=$raw$precheckNote",
                        dailyNoteErrorCode = r.code,
                        dailyNoteLoading = false
                    )
                }
            }
        }
    }

    // ==================== 极验预检（2026-09 定罪修复） ====================
    // 常量 GEETEST_VALID/INVALID/UNKNOWN 已并入顶部 companion object

    /**
     * 调极验官方二次校验接口 validate.php，裁决 WebView 产出 validate 的真伪。
     *
     * 背景：米哈游 verifyVerification 返回 -1 空 message 的真因是
     * 极验二次校验不认该 validate（WebView 里 gt.js 降级产出本地假值，
     * 实测极验返回 {"seccode":"false"}）。本函数让 App 在提交失败后
     * 直接问极验"这个 validate 是真的吗"，把根因透明化：
     * - [GEETEST_INVALID]：假验证（环境被识别/降级）→ 用户重试
     * - [GEETEST_VALID]：真验证但米哈游拒绝 → 风控/IP 问题
     * - [GEETEST_UNKNOWN]：预检接口不可达
     *
     * 注意：仅在 verifyVerification 已失败后调用（预检可能消费 challenge，
     * 但失败流程本就要重新 createVerification，无副作用）。
     */
    private suspend fun precheckGeetest(gt: String?, geetest: GeetestResult): Int {
        if (gt.isNullOrBlank() || geetest.isV4) return GEETEST_UNKNOWN
        val challenge = geetest.raw.get("geetest_challenge")?.asStringSafe() ?: return GEETEST_UNKNOWN
        val validate = geetest.raw.get("geetest_validate")?.asStringSafe() ?: return GEETEST_UNKNOWN
        val seccode = geetest.raw.get("geetest_seccode")?.asStringSafe()
            ?: "$validate|jordan"
        return try {
            withContext(Dispatchers.IO) {
                val form = okhttp3.FormBody.Builder()
                    .add("challenge", challenge)
                    .add("validate", validate)
                    .add("seccode", seccode)
                    .build()
                val url = "https://api.geetest.com/validate.php" +
                    "?gt=$gt&challenge=$challenge&json_format=1"
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "GachaHelper-Precheck/1.0")
                    .post(form)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    AppLog.i(
                        "GeetestPrecheck", "validate.php",
                        "http=${resp.code} body=$body"
                    )
                    when {
                        // 真验证：极验回显 md5(seccode) 形式的 validate
                        body.contains("\"validate\"") &&
                            !body.contains("false", ignoreCase = true) -> GEETEST_VALID
                        // 假验证（用户日志实锤的形态）
                        body.contains("\"seccode\"", true) &&
                            body.contains("false", true) -> GEETEST_INVALID
                        else -> GEETEST_UNKNOWN
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(
                "GeetestPrecheck", "validate.php",
                "预检异常 ${e.javaClass.simpleName}: ${e.message}"
            )
            GEETEST_UNKNOWN
        }
    }

    /** 用户关闭验证对话框 */
    fun dismissCaptcha() {
        _uiState.value = _uiState.value.copy(captcha = null)
    }

    /**
     * 上报验证组件异常（资源加载失败 / 组件降级 / 组件报错）。
     *
     * 弹窗保持打开，用户可据此判断是网络问题还是环境问题，
     * 避免"点了没反应"的静默失败。
     */
    fun reportCaptchaError(message: String) {
        _uiState.value = _uiState.value.copy(dailyNoteError = message)
    }

    // ==================== 日志导出（已迁出） ====================
    // 2026-09-20：openLogExportDialog / dismissLogExportDialog /
    // requestShareLogFile / copyLogToClipboard / revealLogPathInClipboard
    // 五方法已整体迁至 SettingsViewModel——日志导出入口从首页右上角
    // 挪到「设置 → 关于」区块（appLog 注入同步移除）。
}

/**
 * 每日便笺快照的本地持久化。
 *
 * 便笺接口有频率限制与风控（1034/5003），因此改为"每个自然日只同步一次、
 * 其余时间本地推算"：快照以 JSON 存在独立的 DataStore 里，并记录归属 UID；
 * UID 不匹配（换号、重新登录）时视为无快照，避免拿别的账号的数据继续推算。
 */
private val Context.dailyNoteStore by preferencesDataStore(name = "daily_note_store")

@Singleton
class DailyNoteRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_SNAPSHOT = stringPreferencesKey("snapshot_json")

        /**
         * 两个时间戳是否落在同一个自然日（本地时区，0 点切换）。
         *
         * 采用自然日而非"间隔 24 小时"，与用户直觉一致：每天 0 点后首次
         * 启动即同步一次当日数据。
         */
        fun isSameLocalDay(first: Long, second: Long = System.currentTimeMillis()): Boolean {
            val a = Calendar.getInstance().apply { timeInMillis = first }
            val b = Calendar.getInstance().apply { timeInMillis = second }
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
    }

    /** 落盘结构：归属 UID + 快照本体（Gson 序列化，字段随 DailyNoteData 演进） */
    private data class Snapshot(val uid: String, val data: DailyNoteData)

    private val gson = Gson()

    /** 读取 [uid] 对应的本地快照；无快照或归属不符时返回 null */
    suspend fun load(uid: String): DailyNoteData? = withContext(Dispatchers.IO) {
        val raw = context.dailyNoteStore.data.first()[KEY_SNAPSHOT] ?: return@withContext null
        runCatching { gson.fromJson(raw, Snapshot::class.java) }
            .getOrNull()
            ?.takeIf { it.uid == uid }
            ?.data
    }

    /** 写入快照（覆盖式，正常情况下一天一次） */
    suspend fun save(uid: String, data: DailyNoteData) = withContext(Dispatchers.IO) {
        val json = gson.toJson(Snapshot(uid, data))
        context.dailyNoteStore.edit { it[KEY_SNAPSHOT] = json }
    }

    /**
     * 读取最近一次快照，不校验归属。
     *
     * 供阈值提醒调度使用：闹钟到点时进程可能是被系统刚拉起的，
     * 此时只需要"当前锚点"来判定是否达标，不关心当时登录的是哪个号
     * （换号/退出时会由调用方清理并取消提醒）。
     */
    suspend fun loadLatest(): DailyNoteData? = withContext(Dispatchers.IO) {
        val raw = context.dailyNoteStore.data.first()[KEY_SNAPSHOT] ?: return@withContext null
        runCatching { gson.fromJson(raw, Snapshot::class.java) }.getOrNull()?.data
    }

    /** 清空快照：退出登录 / 清空数据时调用 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        context.dailyNoteStore.edit { it.remove(KEY_SNAPSHOT) }
    }
}
