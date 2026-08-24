package com.genshin.gachahelper.remote

import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaItemDatabase
import com.genshin.gachahelper.data.model.ItemType
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 接口响应解析器（硬编码米游社官方响应结构）
 *
 * 之前从 ConfigStore.ApiConfig.mapping 动态读取字段路径，但官方响应结构是固定的：
 *   retcode/message/data.list → [{ name, item_type, rank_type, time, id }]
 * 自定义配置对解析行为没有任何可观察效果。改为直接硬编码，减少间接层与空安全复杂度。
 */
@Singleton
class GachaResponseParser @Inject constructor() {

    /** 解析米游社官方 getGachaLog 响应 */
    fun parseResponse(
        jsonString: String,
        accountId: Long,
        poolType: Int
    ): ParseResult {
        return try {
            val json = JsonParser.parseString(jsonString).asJsonObject

            // 检查返回码
            val retcode = json.get("retcode")?.asInt ?: 0
            val message = json.get("message")?.asString ?: ""
            if (retcode != 0) {
                return ParseResult.Error("接口返回错误: $message (code: $retcode)")
            }

            // data.list 路径
            val data = json.getAsJsonObject("data")
                ?: return ParseResult.Error("响应缺少 data")
            val listArray: JsonArray = data.getAsJsonArray("list")
                ?: return ParseResult.Error("响应缺少 data.list")

            val records = mutableListOf<GachaRecordEntity>()

            for (item in listArray) {
                val itemObj = item.asJsonObject
                try {
                    val itemName = getString(itemObj, "name")
                    val itemTypeStr = getString(itemObj, "item_type")
                    val rankStr = getString(itemObj, "rank_type")

                    // rank_type 缺失时根据物品名称推断
                    val rarity = if (rankStr.isNotBlank()) {
                        parseRarity(rankStr)
                    } else {
                        GachaItemDatabase.inferRarity(itemName, itemTypeStr)
                    }

                    records.add(
                        GachaRecordEntity(
                            accountId = accountId,
                            poolType = poolType,
                            itemName = itemName,
                            itemType = parseItemType(itemTypeStr),
                            rarity = rarity,
                            time = getString(itemObj, "time"),
                            orderNumber = getString(itemObj, "id")
                        )
                    )
                } catch (_: Exception) {
                    // 单条解析失败不影响整体
                }
            }

            // 官方 API 返回 list 为空时即无下一页（对应 stopWhenEmpty=true）
            val hasMore = records.isNotEmpty()

            ParseResult.Success(records, hasMore)
        } catch (e: Exception) {
            ParseResult.Error("解析失败: ${e.message}")
        }
    }

    private fun getString(obj: JsonObject, key: String): String {
        val element = obj.get(key)
        return element?.asString ?: ""
    }

    private fun parseRarity(value: String): Int {
        // 支持多种格式："5", "5星", "S" 等
        return when {
            value.equals("S", ignoreCase = true) -> 5
            value.equals("A", ignoreCase = true) -> 4
            value.equals("B", ignoreCase = true) -> 3
            // 先尝试精确数值匹配，避免 "15" / "S5" 被误判为 5 星
            value.matches(Regex("\\d+")) -> value.toIntOrNull()?.coerceIn(3, 5) ?: 3
            value.contains("5") -> 5
            value.contains("4") -> 4
            value.contains("3") -> 3
            else -> value.toIntOrNull() ?: 3
        }
    }

    private fun parseItemType(value: String): Int {
        return when {
            value.contains("角色") || value.equals("character", ignoreCase = true) -> ItemType.CHARACTER.value
            value.contains("武器") || value.equals("weapon", ignoreCase = true) -> ItemType.WEAPON.value
            else -> ItemType.OTHER.value
        }
    }

    sealed class ParseResult {
        data class Success(val records: List<GachaRecordEntity>, val hasMore: Boolean) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }
}
