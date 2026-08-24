package com.genshin.gachahelper.remote

import com.genshin.gachahelper.config.model.ApiConfig
import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.model.ItemType
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 接口响应解析器
 * 根据配置文件中的 mapping 字段，将接口返回的 JSON 解析为统一的 GachaRecordEntity 列表
 */
@Singleton
class GachaResponseParser @Inject constructor() {

    /**
     * 解析接口返回的 JSON 字符串
     * @param jsonString 原始 JSON 响应
     * @param config 接口配置（包含 mapping 信息）
     * @param accountId 关联的账号 ID
     * @param poolType 卡池类型
     * @return 解析出的记录列表 + 是否还有下一页
     */
    fun parseResponse(
        jsonString: String,
        config: ApiConfig,
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

            // 按 listPath 找到数据列表
            val listElement = navigatePath(json, config.mapping.listPath)
            val listArray = listElement?.asJsonArray
                ?: return ParseResult.Error("无法找到数据列表路径: ${config.mapping.listPath}")

            val records = mutableListOf<GachaRecordEntity>()
            val mapping = config.mapping

            for (item in listArray) {
                val itemObj = item.asJsonObject
                try {
                    val record = GachaRecordEntity(
                        accountId = accountId,
                        poolType = poolType,
                        itemName = getStringValue(itemObj, mapping.itemName),
                        itemType = parseItemType(getStringValue(itemObj, mapping.itemType)),
                        rarity = parseRarity(getStringValue(itemObj, mapping.rarity)),
                        time = getStringValue(itemObj, mapping.time),
                        orderNumber = getStringValue(itemObj, mapping.orderNumber)
                    )
                    records.add(record)
                } catch (_: Exception) {
                    // 单条解析失败不影响整体
                }
            }

            // 判断是否还有下一页
            val hasMore = if (config.pagination.stopWhenEmpty) {
                records.isNotEmpty()
            } else {
                config.pagination.hasMoreField?.let { field ->
                    navigatePath(json, field)?.asBoolean ?: false
                } ?: records.isNotEmpty()
            }

            ParseResult.Success(records, hasMore)
        } catch (e: Exception) {
            ParseResult.Error("解析失败: ${e.message}")
        }
    }

    /**
     * 按点分路径导航 JSON 对象
     */
    private fun navigatePath(json: JsonObject, path: String): com.google.gson.JsonElement? {
        val parts = path.split(".")
        var current: com.google.gson.JsonElement = json
        for (part in parts) {
            if (current is JsonObject) {
                current = current.get(part) ?: return null
            } else {
                return null
            }
        }
        return current
    }

    private fun getStringValue(obj: JsonObject, key: String): String {
        val element = obj.get(key)
        return element?.asString ?: ""
    }

    private fun parseRarity(value: String): Int {
        // 支持多种格式："5", "5星", "S" 等
        return when {
            value.contains("5") -> 5
            value.contains("4") -> 4
            value.contains("3") -> 3
            value.equals("S", ignoreCase = true) -> 5
            value.equals("A", ignoreCase = true) -> 4
            value.equals("B", ignoreCase = true) -> 3
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
