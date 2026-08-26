package com.genshin.gachahelper.sync

import com.genshin.gachahelper.auth.ApiResult
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.auth.MihoyoApiService
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.data.local.entity.AccountEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.remote.GachaApiClient
import com.genshin.gachahelper.remote.GachaResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步状态
 */
sealed class SyncState {
    data object Idle : SyncState()
    data class Loading(val message: String) : SyncState()
    data class Progress(
        val currentPool: String,
        val totalRecords: Int,
        val newRecords: Int
    ) : SyncState()
    data class Success(val totalNew: Int, val totalRecords: Int) : SyncState()
    data class Error(val message: String) : SyncState()
    /** -110 限流：需要冷却，不能立即重试 */
    data class RateLimited(val cooldownSeconds: Int) : SyncState()
}

/**
 * 抽卡同步服务
 * 负责编排整个同步流程：
 *
 * 阶段一：获取/复用 AuthKey（所有池共享同一个 AuthKey）
 * 阶段二：按卡池分页请求 → 解析 → 去重写入
 *
 * -110 处理：遇到 -110 立即停止同步，进入冷却期，不刷新 AuthKey
 * -100 处理：AuthKey 可能过期，清除缓存并重新生成，仅重试一次
 */
@Singleton
class GachaSyncService @Inject constructor(
    private val apiClient: GachaApiClient,
    private val responseParser: GachaResponseParser,
    private val authRepository: AuthRepository,
    private val mihoyoApi: MihoyoApiService,
    private val gachaRepository: GachaRepository,
    private val sessionEventBus: SessionEventBus
) {
    companion object {
        /** -110 冷却时间（秒） */
        private const val RATE_LIMIT_COOLDOWN_SECONDS = 60
        /** -110 后下次同步前的最小间隔（毫秒） */
        private const val RATE_LIMIT_COOLDOWN_MS = RATE_LIMIT_COOLDOWN_SECONDS * 1000L
        /** 分页请求间隔（毫秒）— 避免请求过快触发 -110 */
        private const val PAGE_DELAY_MS = 500L
        /** 池间间隔（毫秒） */
        private const val POOL_DELAY_MS = 1000L
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** 上次 -110 发生的时间戳（毫秒） */
    @Volatile
    private var lastRateLimitedTime: Long = 0L

    /**
     * 执行全量同步（角色池 + 武器池 + 常驻池 + 新手池 + 集录池）
     */
    suspend fun syncAll() = withContext(Dispatchers.IO) {
        if (_syncState.value is SyncState.Loading || _syncState.value is SyncState.Progress) {
            return@withContext
        }

        // ---- 冷却检查：如果刚遇到 -110，不能立即重试 ----
        val now = System.currentTimeMillis()
        val elapsed = now - lastRateLimitedTime
        if (lastRateLimitedTime > 0 && elapsed < RATE_LIMIT_COOLDOWN_MS) {
            val remaining = ((RATE_LIMIT_COOLDOWN_MS - elapsed) / 1000).toInt().coerceAtLeast(1)
            _syncState.value = SyncState.RateLimited(remaining)
            return@withContext
        }

        _syncState.value = SyncState.Loading("准备同步...")

        try {
            // 1. 检查登录状态
            if (!authRepository.isLoggedIn()) {
                throw IllegalStateException("未登录，请先授权米游社")
            }

            val uid = authRepository.getUid()
                ?: throw IllegalStateException("未获取到游戏 UID，请重新授权")
            val server = authRepository.getServer() ?: "cn_gf01"
            val nickname = authRepository.getNickname()

            // ===== UID 校验：同步数据也必须验证归属 UID =====
            // 如果本地已有数据 UID，必须与登录 UID 一致才能继续同步
            val localAccount = gachaRepository.getActiveAccount(null)
            val localDataUid = localAccount?.uid
            if (!localDataUid.isNullOrBlank() && localDataUid != uid) {
                throw IllegalStateException(
                    "UID 不一致：本地数据 UID 为 $localDataUid，登录账号 UID 为 $uid，拒绝同步"
                )
            }

            // ---- 阶段一：获取/复用 AuthKey ----
            // AuthKey 优先复用缓存（20 小时有效），不每次重新获取
            _syncState.value = SyncState.Loading("获取授权凭证...")

            // 清除可能过期的不匹配 UID 的 AuthKey
            authRepository.validateAuthKeyForUid(uid)

            val authKey = when (val result = mihoyoApi.getValidAuthKey()) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> throw IllegalStateException("获取 authkey 失败: ${result.message}")
            }

            // 确保账号已存在
            val accountId = ensureAccount(uid, server, nickname)

            // ---- 阶段二：按卡池分页查询 ----
            // 所有池复用同一个 AuthKey，不重新获取
            val pools = listOf(
                GachaType.CHARACTER,
                GachaType.CHARACTER_2,
                GachaType.WEAPON,
                GachaType.STANDARD,
                GachaType.NOVICE,
                GachaType.CHRONICLED
            )

            var totalNew = 0
            var totalRecords = 0

            for ((index, pool) in pools.withIndex()) {
                if (index > 0) delay(POOL_DELAY_MS)

                _syncState.value = SyncState.Loading("正在同步${pool.displayName}...")

                val result = syncPool(accountId, pool, authKey, uid, server)
                totalNew += result.newCount
                totalRecords += result.totalCount

                _syncState.value = SyncState.Progress(
                    currentPool = pool.displayName,
                    totalRecords = totalRecords,
                    newRecords = totalNew
                )
            }

            // 5. 更新最后同步时间
            gachaRepository.updateLastSyncTime(accountId, System.currentTimeMillis())

            _syncState.value = SyncState.Success(totalNew, totalRecords)
            sessionEventBus.emit(SessionEvent.DataSynced)

        } catch (e: RateLimitedException) {
            // -110：进入冷却，不刷新 AuthKey
            lastRateLimitedTime = System.currentTimeMillis()
            _syncState.value = SyncState.RateLimited(RATE_LIMIT_COOLDOWN_SECONDS)
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "同步失败")
        }
    }

    /**
     * 同步单个卡池
     * 如果遇到 -110，抛出 RateLimitedException 由上层处理
     * 如果遇到 -100（AuthKey 失效），清除缓存并重新获取 AuthKey，仅重试一次
     */
    private suspend fun syncPool(
        accountId: Long,
        pool: GachaType,
        authKey: String,
        uid: String,
        server: String
    ): PoolSyncResult {
        return syncPoolInternal(accountId, pool, authKey, uid, server, isRetry = false)
    }

    private suspend fun syncPoolInternal(
        accountId: Long,
        pool: GachaType,
        authKey: String,
        uid: String,
        server: String,
        isRetry: Boolean
    ): PoolSyncResult {
        var page = 1
        var newCount = 0
        var totalCount = 0
        var hasMore = true
        var endId = "0"
        val pageSize = GachaApiClient.PAGE_SIZE

        // 获取本地已有的最大 orderNumber 用于增量判断
        val maxOrderNumber = gachaRepository.getMaxOrderNumber(accountId, pool.value)

        while (hasMore) {
            val placeholders = buildPlaceholders(
                authKey = authKey,
                uid = uid,
                server = server,
                page = page,
                gachaType = pool.value,
                pageSize = pageSize,
                endId = endId
            )

            val response = apiClient.fetchGachaPage(placeholders)
            if (response.isFailure) {
                throw response.exceptionOrNull() ?: Exception("请求失败")
            }

            val parseResult = responseParser.parseResponse(
                jsonString = response.getOrThrow(),
                accountId = accountId,
                poolType = pool.value
            )

            when (parseResult) {
                is GachaResponseParser.ParseResult.RateLimited -> {
                    // -110：立即停止，不继续请求
                    throw RateLimitedException("访问过于频繁 (-110)")
                }

                is GachaResponseParser.ParseResult.AuthKeyInvalid -> {
                    // -100：AuthKey 失效，清除缓存
                    authRepository.clearAuthKey()
                    if (!isRetry) {
                        // 重新获取 AuthKey，仅重试当前池一次
                        val newAuthKey = when (val result = mihoyoApi.getValidAuthKey()) {
                            is ApiResult.Success -> result.data
                            is ApiResult.Error -> throw IllegalStateException("authkey 重新获取失败: ${result.message}")
                        }
                        // 递归调用自身（带 isRetry=true），用新的 AuthKey 从当前页继续
                        return syncPoolInternal(accountId, pool, newAuthKey, uid, server, isRetry = true)
                    } else {
                        throw IllegalStateException("AuthKey 失效且重试仍失败")
                    }
                }

                is GachaResponseParser.ParseResult.Error -> {
                    throw Exception(parseResult.message)
                }

                is GachaResponseParser.ParseResult.Success -> {
                    val records = parseResult.records
                    totalCount += records.size

                    // 更新 end_id 为最后一条记录的 orderNumber（用于翻页）
                    if (records.isNotEmpty()) {
                        endId = records.last().orderNumber
                    }

                    // 增量判断：如果遇到 orderNumber <= 本地最大值，说明已同步过
                    if (maxOrderNumber != null && records.isNotEmpty()) {
                        val maxOrderLong = maxOrderNumber.toLongOrNull() ?: 0L
                        val existingRecords = records.filter {
                            (it.orderNumber.toLongOrNull() ?: 0L) <= maxOrderLong
                        }
                        if (existingRecords.isNotEmpty()) {
                            val newRecords = records.filter {
                                (it.orderNumber.toLongOrNull() ?: 0L) > maxOrderLong
                            }
                            if (newRecords.isNotEmpty()) {
                                val inserted = gachaRepository.insertRecords(newRecords)
                                newCount += inserted
                            }
                            hasMore = false
                            break
                        }
                    }

                    if (records.isNotEmpty()) {
                        val inserted = gachaRepository.insertRecords(records)
                        newCount += inserted
                    }

                    hasMore = parseResult.hasMore && records.isNotEmpty()
                    page++
                }
            }

            // 分页请求间隔，避免请求过快触发 -110
            delay(PAGE_DELAY_MS)
        }

        return PoolSyncResult(newCount, totalCount)
    }

    private fun buildPlaceholders(
        authKey: String,
        uid: String,
        server: String,
        page: Int,
        gachaType: Int,
        pageSize: Int,
        endId: String
    ): Map<String, String> {
        return mapOf(
            "authkey" to authKey,
            "uid" to uid,
            "region" to server,
            "server" to server,
            "page" to page.toString(),
            "gacha_type" to gachaType.toString(),
            "gacha_id" to "",
            "size" to pageSize.toString(),
            "page_size" to pageSize.toString(),
            "end_id" to endId,
            "timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "token" to authKey
        )
    }

    private suspend fun ensureAccount(uid: String, server: String, nickname: String?): Long {
        val existing = gachaRepository.getAccountByUid(uid)
        return if (existing != null) {
            existing.id
        } else {
            gachaRepository.insertAccount(
                AccountEntity(
                    uid = uid,
                    server = server,
                    nickname = nickname,
                    createTime = System.currentTimeMillis()
                )
            )
        }
    }

    private data class PoolSyncResult(
        val newCount: Int,
        val totalCount: Int
    )

    /** -110 限流异常：用于区分普通错误和限流 */
    private class RateLimitedException(message: String) : Exception(message)

    fun resetState() {
        _syncState.value = SyncState.Idle
    }
}
