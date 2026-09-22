package com.genshin.gachahelper.backup

import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.data.repository.GachaRepository
import com.genshin.gachahelper.sync.GachaDataImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份动作来源（仅用于状态文案与日志，不影响流程）
 */
object BackupTrigger {
    const val AUTO_SYNC = "同步后自动备份"
    const val MANUAL = "手动备份"
}

/**
 * 抽卡记录 WebDAV 备份管理器（2026-09-21 新增）
 *
 * 职责：
 * 1. 复用 [GachaDataImporter.exportToString] 把当前账号全量抽卡记录序列化为 UIGF JSON；
 * 2. 通过 [WebDavClient] 上传到远端 `<remoteDir>/` 下：
 *    - `gacha_<uid>_<yyyyMMdd_HHmmss>.json`：带时间戳的归档副本（保留历史，不删除旧文件）
 *    - `gacha_<uid>_latest.json`：覆盖式最新副本（便于快速取回）
 * 3. 把本次结果写入 [WebDavConfigStore]（时间 + 成功/失败原因 + 文件名），供设置页展示。
 *
 * 只新增/覆盖上述两个文件，绝不删除远端其它文件。
 */
@Singleton
class GachaBackupManager @Inject constructor(
    private val configStore: WebDavConfigStore,
    private val webDavClient: WebDavClient,
    private val gachaDataImporter: GachaDataImporter,
    private val gachaRepository: GachaRepository,
    private val authRepository: AuthRepository
) {

    /**
     * 连接测试（设置页「测试连接」按钮）：用当前已保存的配置探测服务器。
     */
    suspend fun testConnection(): WebDavResult = withContext(Dispatchers.IO) {
        val config = runCatching { configStore.current() }.getOrElse {
            return@withContext WebDavResult.Failure("读取配置失败：${it.message ?: "未知错误"}")
        }
        if (!config.isConfigured) {
            return@withContext WebDavResult.Failure("请先填写服务器地址")
        }
        webDavClient.testConnection(config)
    }

    /**
     * 同步成功后的自动备份入口：开关未开或未配置时静默跳过，任何异常都不向上抛，
     * 避免影响抽卡记录同步本身的成功状态。
     */
    suspend fun autoBackup() {
        val config = runCatching { configStore.current() }.getOrNull() ?: return
        if (!config.enabled || !config.isConfigured) return
        runCatching { backupNow(BackupTrigger.AUTO_SYNC) }
    }

    /**
     * 立即执行一次备份（手动 / 自动共用）。
     *
     * @return 执行结果，同时已落库到配置存储供 UI 展示
     */
    suspend fun backupNow(trigger: String): WebDavResult = withContext(Dispatchers.IO) {
        val config = runCatching { configStore.current() }.getOrElse {
            return@withContext WebDavResult.Failure("读取配置失败：${it.message ?: "未知错误"}")
        }

        if (!config.isConfigured) {
            return@withContext finish(WebDavResult.Failure("未配置服务器地址"))
        }

        // 取当前账号：优先登录 UID，未登录时回退到本地数据账号
        val uid = authRepository.getUid()
            ?: runCatching { gachaRepository.getActiveAccount(null)?.uid }.getOrNull()
        if (uid.isNullOrBlank()) {
            return@withContext finish(WebDavResult.Failure("没有可备份的抽卡数据"))
        }

        val json = runCatching { gachaDataImporter.exportToString(uid) }.getOrElse {
            return@withContext finish(WebDavResult.Failure("导出数据失败：${it.message ?: "未知错误"}"))
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val recordCount = countRecords(json)

        val dir = config.remoteDir.ifBlank { "/GenshinGachaHelper" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val archiveName = "gacha_${uid}_${timestamp}.json"
        val latestName = "gacha_${uid}_latest.json"

        // 1) 带时间戳的归档副本
        when (val archive = webDavClient.upload(config, dir, archiveName, bytes)) {
            is WebDavResult.Failure -> return@withContext finish(archive, archiveName)
            is WebDavResult.Success -> Unit
        }

        // 2) 覆盖式最新副本（失败不回滚归档，仅提示）
        val latest = webDavClient.upload(config, dir, latestName, bytes)

        val result = when (latest) {
            is WebDavResult.Success -> WebDavResult.Success(
                "备份成功：$recordCount 条记录 · $trigger"
            )
            is WebDavResult.Failure -> WebDavResult.Failure(
                "归档已上传，但更新 latest 失败：${latest.message}"
            )
        }
        finish(result, archiveName)
    }

    /** 记录结果并返回原结果，供调用方直接 return */
    private suspend fun finish(result: WebDavResult, fileName: String = ""): WebDavResult {
        val statusText = when (result) {
            is WebDavResult.Success -> result.message
            is WebDavResult.Failure -> "失败：${result.message}"
        }
        runCatching { configStore.recordBackupResult(statusText, fileName) }
        return result
    }

    /**
     * 粗略统计导出 JSON 中的记录条数（用于状态文案，不作为数据校验依据）。
     */
    private fun countRecords(json: String): Int {
        val key = "\"gacha_type\""
        var count = 0
        var index = json.indexOf(key)
        while (index >= 0) {
            count++
            index = json.indexOf(key, index + key.length)
        }
        return count
    }
}
