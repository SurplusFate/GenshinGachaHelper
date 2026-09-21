package com.genshin.gachahelper.auth

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * gson 安全访问扩展函数。
 *
 * ── 背景 ────────────────────────────────────────────────────────
 * gson 的 `JsonObject.getAsJsonObject(String key)` / `getAsJsonArray(String key)`
 * 内部直接做 `(JsonObject) members.get(key)`——**没有任何类型检查**。
 * 当服务端返回 `{"data": null, ...}`（风控拒绝、限流、降级时常出现）时，
 * `members.get("data")` 返回 `JsonNull` 实例，强转时抛
 * `ClassCastException: JsonNull cannot be cast to JsonObject`。
 * 在 R8 混淆后表现为 `q6.d cannot be cast to q6.e`（JsonNull→JsonObject）。
 *
 * 即使加了 `if (!elem.isJsonObject()) return ...` 守卫，R8 也可能在优化时
 * 把守卫和后续 `.asJsonObject` 合并成单个 cast，让守卫失效（参见
 * VerificationService.kt line 393 的历史 bug）。
 *
 * 因此这里使用 Kotlin 的 `is` / `as?` 安全转型 + `takeIf` 显式守卫，
 * 让守卫变成不可消除的运行时类型检查，不再受 R8 优化影响。
 * ──────────────────────────────────────────────────────────────
 */

/**
 * 安全读取子 JsonObject。
 *
 * 行为：
 * - 键不存在 → null
 * - 键存在但值为 JsonNull / JsonPrimitive / JsonArray → null
 * - 键存在且值为 JsonObject → 返回该对象
 *
 * 与 `getAsJsonObject(key)` 的区别：**不会抛 ClassCastException**。
 */
fun JsonObject.getAsJsonObjectSafe(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

/**
 * 安全读取子 JsonArray。
 *
 * 与 `getAsJsonArray(key)` 的区别：**不会抛 ClassCastException**。
 */
fun JsonObject.getAsJsonArraySafe(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/**
 * 安全读取字符串值。
 *
 * 行为：
 * - JsonNull → null
 * - JsonPrimitive 字符串 → 字符串内容
 * - JsonPrimitive 数字/布尔 → null（不要把数字当作字符串，避免类型歧义）
 * - JsonObject / JsonArray → null
 *
 * 与 `getAsString()` 的区别：**不会抛 UnsupportedOperationException 或
 * IllegalStateException**。所有非字符串场景统一返回 null。
 */
fun JsonElement.asStringSafe(): String? =
    if (this.isJsonPrimitive && (this as JsonPrimitive).isString)
        (this as JsonPrimitive).asString else null

/**
 * 安全转 JsonObject（顶层元素，不限父类型）。
 *
 * 与 `JsonParser.parseString(...).asJsonObject` 的区别：**不会抛
 * ClassCastException**——当顶层是 JsonNull / JsonPrimitive / JsonArray 时返回 null。
 */
fun JsonElement.asJsonObjectOrNullSafe(): JsonObject? =
    if (this.isJsonObject) this.asJsonObject else null

/**
 * 安全读取整数值。
 *
 * 行为：
 * - JsonNull → default
 * - JsonPrimitive 数字 → 转 Int（失败时返回 default）
 * - JsonPrimitive 数字字符串（如 "200"）→ 转 Int
 * - JsonPrimitive 布尔/字符串 → default
 * - JsonObject / JsonArray → default
 *
 * 与 `getAsInt()` 的区别：**不会抛 UnsupportedOperationException / NumberFormatException**。
 */
fun JsonElement.asIntSafe(default: Int = 0): Int {
    if (!this.isJsonPrimitive) return default
    val prim = this.asJsonPrimitive
    if (prim.isNumber) return runCatching { prim.asInt }.getOrDefault(default)
    if (prim.isString) {
        val s = prim.asString.trim()
        return s.toIntOrNull() ?: default
    }
    return default
}

/**
 * 安全读取长整数值（与 asIntSafe 同语义，处理 Long 场景）。
 */
fun JsonElement.asLongSafe(default: Long = 0L): Long {
    if (!this.isJsonPrimitive) return default
    val prim = this.asJsonPrimitive
    if (prim.isNumber) return runCatching { prim.asLong }.getOrDefault(default)
    if (prim.isString) {
        val s = prim.asString.trim()
        return s.toLongOrNull() ?: default
    }
    return default
}

/**
 * 安全读取布尔值。
 *
 * 行为：
 * - JsonPrimitive 布尔 → 转 Boolean
 * - JsonPrimitive 数字 → 非零为 true
 * - JsonPrimitive 字符串 → "true"/"1" 为 true
 * - 其他 → default
 */
fun JsonElement.asBooleanSafe(default: Boolean = false): Boolean {
    if (!this.isJsonPrimitive) return default
    val prim = this.asJsonPrimitive
    if (prim.isBoolean) return prim.asBoolean
    if (prim.isNumber) return prim.asInt != 0
    if (prim.isString) {
        val s = prim.asString.trim().lowercase()
        return s == "true" || s == "1"
    }
    return default
}