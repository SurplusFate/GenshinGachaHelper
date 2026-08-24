package com.genshin.gachahelper.sync

import com.genshin.gachahelper.auth.ApiResult
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.auth.MihoyoApiService
import com.genshin.gachahelper.config.store.ConfigStore
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
}

/**
 * 抽卡同步服务
 * 负责编排整个同步流程：
 * 1. 通过 stoken 生成 authkey
 * 2. 按卡池分页请求 → 解析 → 去重写入
 */
@Singleton
class GachaSyncService @Inject constructor(
    private val apiClient: GachaApiClient,
    private val responseParser: GachaResponseParser,
    private val configStore: ConfigStore,
    private val authRepository: AuthRepository,
    private val mihoyoApi: MihoyoApiService,
    private val gachaRepository: GachaRepository,
    private val sessionEventBus: SessionEventBus
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * 执行全量同步（角色池 + 武器池 + 常驻池）
     */
    suspend fun syncAll() = withContext(Dispatchers.IO) {
        if (_syncState.value is SyncState.Loading || _syncState.value is SyncState.Progress) {
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

            // 2. 生成/获取 authkey（每次同步前确保 authkey 有效）
            _syncState.value = SyncState.Loading("获取授权凭证...")

            val authKey = when (val result = mihoyoApi.getValidAuthKey()) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> throw IllegalStateException("获取 authkey 失败: ${result.message}")
            }

            // 3. 获取接口配置
            val config = configStore.getCurrentConfig()

            // 4. 确保账号已存在
            val accountId = ensureAccount(uid, server, nickname)

            // 5. 按卡池同步
            val pools = listOf(
                GachaType.CHARACTER,
                GachaType.WEAPON,
                GachaType.STANDARD,
                GachaType.CHRONICLED
            )

            var totalNew = 0
            var totalRecords = 0

            for (pool in pools) {
                _syncState.value = SyncState.Loading("正在同步${pool.displayName}...")

                val result = syncPool(accountId, pool, config, authKey, uid, server)
                totalNew += result.newCount
                totalRecords += result.totalCount

                _syncState.value = SyncState.Progress(
                    currentPool = pool.displayName,
                    totalRecords = totalRecords,
                    newRecords = totalNew
                )
            }

            // 6. 更新最后同步时间
            gachaRepository.updateLastSyncTime(accountId, System.currentTimeMillis())

            _syncState.value = SyncState.Success(totalNew, totalRecords)
            // 通知全局：同步完成，其他 ViewModel 通过事件总线刷新（替代旁路监听 syncState）
            sessionEventBus.emit(SessionEvent.DataSynced)

        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "同步失败")
        }
    }

    /**
     * 同步单个卡池
     */
    private suspend fun syncPool(
        accountId: Long,
        pool: GachaType,
        config: com.genshin.gachahelper.config.model.ApiConfig,
        authKey: String,
        uid: String,
        server: String
    ): PoolSyncResult {
        var page = 1
        var newCount = 0
        var totalCount = 0
        var hasMore = true
        var endId = "0"
        val pageSize = config.pagination.pageSize

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

            val response = apiClient.fetchGachaPage(config, placeholders)
            if (response.isFailure) {
                throw response.exceptionOrNull() ?: Exception("请求失败")
            }

            val parseResult = responseParser.parseResponse(
                jsonString = response.getOrThrow(),
                config = config,
                accountId = accountId,
                poolType = pool.value
            )

            when (parseResult) {
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
                    // 注意：orderNumber 是 String，必须转 Long 比较，否则字典序错误
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

            // 避免请求过快（用 delay 而非 Thread.sleep，避免阻塞 IO 线程）
            delay(300)
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

    fun resetState() {
        _syncState.value = SyncState.Idle
    }
}
