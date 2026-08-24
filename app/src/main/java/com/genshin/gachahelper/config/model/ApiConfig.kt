package com.genshin.gachahelper.config.model

import kotlinx.serialization.Serializable

/**
 * 接口配置数据模型（Kotlin Serialization JSON）
 */
@Serializable
data class ApiConfig(
    val version: String,
    val api: ApiConfigEndpoint,
    val params: Map<String, String>,
    val pagination: ApiConfigPagination,
    val mapping: ApiConfigMapping
) {
    /**
     * 校验配置是否合法（必填字段检查）
     * 返回 null 表示合法，否则返回错误信息
     */
    fun validate(): String? {
        if (version.isBlank()) return "version 不能为空"
        if (api.url.isBlank()) return "api.url 不能为空"
        if (api.method.isBlank()) return "api.method 不能为空"
        if (mapping.listPath.isBlank()) return "mapping.list_path 不能为空"
        if (mapping.itemName.isBlank()) return "mapping.item_name 不能为空"
        if (mapping.itemType.isBlank()) return "mapping.item_type 不能为空"
        if (mapping.rarity.isBlank()) return "mapping.rarity 不能为空"
        if (mapping.time.isBlank()) return "mapping.time 不能为空"
        if (mapping.orderNumber.isBlank()) return "mapping.order_number 不能为空"
        return null
    }
}

@Serializable
data class ApiConfigEndpoint(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class ApiConfigPagination(
    val hasMoreField: String? = null,
    val pageSize: Int = 20,
    val stopWhenEmpty: Boolean = true
)

@Serializable
data class ApiConfigMapping(
    val listPath: String,
    val itemName: String,
    val itemType: String,
    val rarity: String,
    val time: String,
    val orderNumber: String
)
