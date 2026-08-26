package com.genshin.gachahelper.sync

import android.content.Context
import android.net.Uri
import com.genshin.gachahelper.data.local.entity.AccountEntity
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.model.GachaItemDatabase
import com.genshin.gachahelper.data.model.ItemType
import com.genshin.gachahelper.data.model.parseItemType
import com.genshin.gachahelper.data.model.parseRarity
import com.genshin.gachahelper.data.repository.GachaRepository
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入结果
 */
data class ImportResult(
    val success: Boolean,
    val totalImported: Int,
    val skipped: Int,
    val uid: String,
    val message: String,
    val errors: List<String> = emptyList()
)

/**
 * 抽卡历史数据导入器
 *
 * 支持 UIGF (Uniformed GachaLog Record Format) 格式导入：
 * - UIGF v3.x: { info: {...}, list: [...] }
 * - UIGF v4.0: { info: {...}, hk4e: { uid, region, list: [...] } }
 * - 兼容其他常见格式（纯数组、list 顶层等）
 *
 * UIGF 字段映射：
 * - gacha_type → poolType (301/302/200)
 * - time       → time
 * - name       → itemName
 * - item_type  → itemType (角色/武器)
 * - rank_type  → rarity (5/4/3)
 * - id         → orderNumber
 */
@Singleton
class GachaDataImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gachaRepository: GachaRepository
) {

    /**
     * 从文件 URI 导入抽卡记录
     * @param authUid 已登录用户的 UID（未登录时传 null）
     * @param localDataUid 当前本地数据绑定的 UID（无数据时传 null）
     */
    suspend fun importFromUri(
        uri: Uri,
        authUid: String? = null,
        localDataUid: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            }
            if (jsonString.isNullOrBlank()) {
                return@withContext ImportResult(false, 0, 0, "", "无法读取文件")
            }

            importFromString(jsonString, authUid, localDataUid)
        } catch (e: Exception) {
            ImportResult(false, 0, 0, "", "读取文件异常: ${e.message}")
        }
    }

    /**
     * 从 JSON 字符串导入抽卡记录
     *
     * UID 校验规则（校验在数据库写入之前）：
     * 1. 已登录：UIGF UID 必须等于登录 UID
     * 2. 未登录但有本地数据：UIGF UID 必须等于本地数据 UID
     * 3. 未登录且无本地数据：建立本地数据 UID = UIGF UID
     *
     * @param authUid 已登录用户的 UID（未登录时传 null）
     * @param localDataUid 当前本地数据绑定的 UID（无数据时传 null）
     */
    suspend fun importFromString(
        jsonString: String,
        authUid: String? = null,
        localDataUid: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        try {
            val root = JsonParser.parseString(jsonString).asJsonObject

            // 自动检测 UIGF 版本
            val parsed: Quad? = when {
                // UIGF v4.0: { info, hk4e: { uid, region, list } }
                root.has("hk4e") -> {
                    val hk4e = root.getAsJsonObject("hk4e")
                    val u = hk4e.get("uid")?.asString ?: ""
                    val r = hk4e.get("region")?.asString ?: "cn_gf01"
                    val list = hk4e.getAsJsonArray("list")
                    Quad(u, r, list, true)
                }
                // UIGF v3.x: { info: { uid }, list: [...] }
                root.has("info") && root.has("list") -> {
                    val info = root.getAsJsonObject("info")
                    val u = info.get("uid")?.asString ?: ""
                    val r = info.get("region")?.asString ?: "cn_gf01"
                    val list = root.getAsJsonArray("list")
                    Quad(u, r, list, false)
                }
                // 纯数组: [...]
                root.isJsonArray -> {
                    val list = root.asJsonArray
                    Quad("", "cn_gf01", list, false)
                }
                // 其他：尝试取 list
                root.has("list") -> {
                    val list = root.getAsJsonArray("list")
                    val info = root.getAsJsonObject("info")
                    val u = info?.get("uid")?.asString ?: ""
                    Quad(u, "cn_gf01", list, false)
                }
                else -> null
            }

            if (parsed == null) {
                return@withContext ImportResult(false, 0, 0, "", "无法识别的 JSON 格式，请确认文件为 UIGF 格式")
            }

            val (uid, region, listArray, isV4) = parsed

            if (listArray == null || listArray.size() == 0) {
                return@withContext ImportResult(true, 0, 0, uid, "文件中没有抽卡记录数据")
            }

            // 确定导入的 UID
            val effectiveUid = uid.ifBlank {
                // 从记录中尝试提取 UID（某些格式把 uid 放在记录中）
                listArray.firstOrNull()?.asJsonObject?.get("uid")?.asString ?: ""
            }

            if (effectiveUid.isBlank()) {
                return@withContext ImportResult(false, 0, 0, "", "无法确定 UID，请确认文件包含 UID 信息")
            }

            // ===== UID 校验（在数据库写入之前） =====
            // 1. 已登录：UIGF UID 必须等于登录 UID
            if (!authUid.isNullOrBlank() && effectiveUid != authUid) {
                return@withContext ImportResult(
                    success = false,
                    totalImported = 0,
                    skipped = 0,
                    uid = effectiveUid,
                    message = "UID 不一致：当前账号 UID 为 $authUid，文件 UID 为 $effectiveUid，拒绝导入"
                )
            }
            // 2. 未登录但有本地数据：UIGF UID 必须等于本地数据 UID
            if (authUid.isNullOrBlank() && !localDataUid.isNullOrBlank() && effectiveUid != localDataUid) {
                return@withContext ImportResult(
                    success = false,
                    totalImported = 0,
                    skipped = 0,
                    uid = effectiveUid,
                    message = "UID 不一致：本地数据 UID 为 $localDataUid，文件 UID 为 $effectiveUid，拒绝导入"
                )
            }
            // 3. 未登录且无本地数据：允许导入，建立本地数据 UID = UIGF UID

            // 查找或创建账号
            var account = gachaRepository.getAccountByUid(effectiveUid)
            if (account == null) {
                val accountId = gachaRepository.insertAccount(
                    AccountEntity(
                        uid = effectiveUid,
                        server = region,
                        nickname = null,
                        createTime = System.currentTimeMillis(),
                        lastSyncTime = System.currentTimeMillis()
                    )
                )
                account = gachaRepository.getAccountByUid(effectiveUid)
            }

            val accountId = account?.id
                ?: return@withContext ImportResult(false, 0, 0, effectiveUid, "创建账号失败")

            // 两遍扫描：第一遍构建 name→rarity 映射（用有 rank_type 的记录）
            val rarityMap = mutableMapOf<String, Int>()
            for (item in listArray) {
                try {
                    val obj = item.asJsonObject
                    val name = obj.get("name")?.asString ?: continue
                    val rankStr = obj.get("rank_type")?.asString
                        ?: obj.get("rarity")?.asString
                        ?: obj.get("rank")?.asString
                        ?: null
                    if (rankStr != null) {
                        val r = parseRarity(rankStr)
                        rarityMap[name] = r
                    }
                } catch (_: Exception) { }
            }

            // 第二遍：解析每条记录，rank_type 缺失时按 rarityMap → GachaItemDatabase 顺序推断
            val records = mutableListOf<GachaRecordEntity>()
            var skipped = 0

            for (item in listArray) {
                try {
                    val obj = item.asJsonObject
                    val record = parseRecord(obj, accountId, rarityMap)
                    if (record != null) {
                        records.add(record)
                    } else {
                        skipped++
                    }
                } catch (e: Exception) {
                    skipped++
                    if (errors.size < 5) {
                        errors.add("记录解析失败: ${e.message}")
                    }
                }
            }

            if (records.isEmpty()) {
                return@withContext ImportResult(true, 0, skipped, effectiveUid, "没有可导入的有效记录", errors)
            }

            // 内容去重：查询已有记录，构建 (poolType, time, itemName) 多重集
            // 当不同来源的数据 ID 格式不同时，用内容指纹做二级去重
            val existingKeys = gachaRepository.getRecordKeysByAccount(accountId)
            val existingMultiset = mutableMapOf<Triple<Int, String, String>, Int>()
            for (key in existingKeys) {
                val triple = Triple(key.poolType, key.time, key.itemName)
                existingMultiset[triple] = (existingMultiset[triple] ?: 0) + 1
            }

            val filteredRecords = mutableListOf<GachaRecordEntity>()
            var contentDupSkipped = 0
            for (record in records) {
                val key = Triple(record.poolType, record.time, record.itemName)
                val existingCount = existingMultiset[key]
                if (existingCount != null && existingCount > 0) {
                    // 内容匹配，跳过并减少计数（处理同一次十连中的重复物品）
                    existingMultiset[key] = existingCount - 1
                    contentDupSkipped++
                } else {
                    filteredRecords.add(record)
                }
            }

            // 批量插入（IGNORE 策略做 ID 级去重作为最后防线）
            val inserted = gachaRepository.insertRecords(filteredRecords)
            val idDupSkipped = filteredRecords.size - inserted

            ImportResult(
                success = true,
                totalImported = inserted,
                skipped = skipped + contentDupSkipped + idDupSkipped,
                uid = effectiveUid,
                message = if (inserted > 0)
                    "导入成功！新增 $inserted 条记录${if (contentDupSkipped + idDupSkipped > 0) "，跳过 ${contentDupSkipped + idDupSkipped} 条重复" else ""}"
                else
                    "所有记录均与已有数据重复，未新增",
                errors = errors
            )
        } catch (e: Exception) {
            ImportResult(false, 0, 0, "", "解析异常: ${e.message}")
        }
    }

    /**
     * 解析单条 UIGF 记录
     * @param rarityMap 从有 rank_type 的记录构建的 name→rarity 映射（第一遍扫描结果）
     */
    private fun parseRecord(obj: JsonObject, accountId: Long, rarityMap: Map<String, Int>): GachaRecordEntity? {
        // gacha_type: 必需，支持 301/400/302/200/100/800
        // 注意：UIGF 文件中 gacha_type 可能是字符串（如 "400"），也可能带 uigf_gacha_type 字段
        // 对于 gacha_type=400（角色活动祈愿-2），保留原始类型，不用 uigf_gacha_type 覆盖
        // 集录祈愿历史上曾用 500 作为 gacha_type，现代 HoYoLab / UIGF 统一是 800，这里两者都接受，
        // 入库前统一标准化成 GachaType.CHRONICLED.value，避免旧导入 / 新导入分裂成两个池。
        val rawGachaType = obj.get("gacha_type")?.let {
            try { it.asInt } catch (_: Exception) { it.asString.toIntOrNull() }
        } ?: run {
            // 回退：少数 UIGF v2/v3 文件只有 uigf_gacha_type 字段，没写 gacha_type
            obj.get("uigf_gacha_type")?.let {
                try { it.asInt } catch (_: Exception) { it.asString.toIntOrNull() }
            } ?: return null
        }

        // 兼容映射：把历史 500（旧版集录 gacha_type）统一到 800，避免新旧数据分池。
        // 其他 gacha_type 保持原值。
        val gachaType = when (rawGachaType) {
            500 -> GachaType.CHRONICLED.value
            else -> rawGachaType
        }

        // 验证是否为有效的卡池类型：
        // 100=新手 / 200=常驻 / 301=角色 / 302=武器 / 400=角色-2 / 800=集录
        val validTypes = setOf(
            GachaType.NOVICE.value,
            GachaType.STANDARD.value,
            GachaType.CHARACTER.value,
            GachaType.WEAPON.value,
            GachaType.CHARACTER_2.value,
            GachaType.CHRONICLED.value
        )
        if (gachaType !in validTypes) return null

        // time: 必需
        val time = obj.get("time")?.asString ?: return null
        if (time.isBlank()) return null

        // name: 必需
        val itemName = obj.get("name")?.asString ?: return null
        if (itemName.isBlank()) return null

        // item_type: 可选，角色/武器
        val itemTypeStr = obj.get("item_type")?.asString ?: ""
        val itemType = parseItemType(itemTypeStr)

        // rank_type: 可选，5/4/3。缺失时根据物品名称推断
        val rankStr = obj.get("rank_type")?.asString
            ?: obj.get("rarity")?.asString
            ?: obj.get("rank")?.asString
            ?: null

        val rarity = if (rankStr != null) {
            // rank_type 存在时使用统一解析器（parseRarity），保持与 API 响应解析一致
            parseRarity(rankStr)
        } else {
            // rank_type 缺失：优先查 rarityMap（同文件中其他记录提供了该物品的稀有度）
            // 其次用 GachaItemDatabase 根据物品名称推断
            rarityMap[itemName] ?: GachaItemDatabase.inferRarity(itemName, itemTypeStr)
        }

        // id / orderNumber: 必需（去重关键）
        val orderNumber = obj.get("id")?.asString
            ?: obj.get("order_number")?.asString
            ?: obj.get("record_id")?.asString
            ?: return null

        return GachaRecordEntity(
            accountId = accountId,
            poolType = gachaType,
            itemName = itemName,
            itemType = itemType,
            rarity = rarity,
            time = time,
            orderNumber = orderNumber
        )
    }

    /**
     * 导出抽卡记录为 UIGF v3.0 JSON 字符串
     */
    suspend fun exportToString(uid: String, includeInfo: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val account = gachaRepository.getAccountByUid(uid)
                ?: return@withContext "{\"info\":{},\"list\":[]}"

            val allRecords = mutableListOf<GachaRecordEntity>()
            for (pool in listOf(GachaType.CHARACTER, GachaType.CHARACTER_2, GachaType.WEAPON, GachaType.STANDARD, GachaType.NOVICE, GachaType.CHRONICLED)) {
                allRecords.addAll(gachaRepository.getRecordsByPool(account.id, pool.value))
            }

            // 按时间排序（从旧到新）
            allRecords.sortBy { it.time }

            val listJson = allRecords.joinToString(",\n") { record ->
                val itemTypeStr = when (record.itemType) {
                    ItemType.CHARACTER.value -> "角色"
                    ItemType.WEAPON.value -> "武器"
                    else -> "其他"
                }
                """    {
        "gacha_type": ${record.poolType},
        "time": "${record.time}",
        "name": "${escapeJson(record.itemName)}",
        "item_type": "$itemTypeStr",
        "rank_type": "${record.rarity}",
        "id": "${record.orderNumber}"
    }""".trimIndent()
            }

            val infoJson = if (includeInfo) {
                val timestamp = System.currentTimeMillis() / 1000
                """"info": {
    "uid": "$uid",
    "lang": "zh-cn",
    "export_time": "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}",
    "export_timestamp": $timestamp,
    "export_app": "GenshinGachaHelper",
    "export_app_version": "1.0.0",
    "uigf_version": "3.0"
},
"""
            } else {
                ""
            }

            """{
$infoJson"list": [
$listJson
]
}"""
        }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private data class Quad(
        val uid: String,
        val region: String,
        val list: com.google.gson.JsonArray?,
        val isV4: Boolean
    )
}
