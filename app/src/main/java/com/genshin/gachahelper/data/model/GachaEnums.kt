package com.genshin.gachahelper.data.model

/**
 * 卡池类型枚举
 */
enum class GachaType(val value: Int, val displayName: String) {
    CHARACTER(301, "角色活动祈愿"),
    CHARACTER_2(400, "角色活动祈愿-2"),
    WEAPON(302, "武器活动祈愿"),
    STANDARD(200, "常驻祈愿"),
    NOVICE(100, "新手祈愿"),
    CHRONICLED(500, "集录祈愿");

    companion object {
        fun fromValue(value: Int): GachaType {
            return entries.firstOrNull { it.value == value } ?: STANDARD
        }
    }
}

/**
 * 物品类型枚举
 */
enum class ItemType(val value: Int) {
    CHARACTER(1),
    WEAPON(2),
    OTHER(3);

    companion object {
        fun fromValue(value: Int): ItemType {
            return entries.firstOrNull { it.value == value } ?: OTHER
        }
    }
}

/**
 * 星级颜色
 */
enum class Rarity(val stars: Int, val color: Long) {
    THREE(3, 0xFF90EE90),
    FOUR(4, 0xFF87CEEB),
    FIVE(5, 0xFFFFD700);

    companion object {
        fun fromStars(stars: Int): Rarity {
            return entries.firstOrNull { it.stars == stars } ?: THREE
        }
    }
}

/**
 * 全局共享的稀有度解析器。
 *
 * 解析策略（按优先级从高到低）：
 * 1. 常见字母等级：S/s=5, A/a=4, B/b=3（部分 UIGF/用户自定义数据使用）
 * 2. 纯数字：精确数值匹配，再 coerceIn(3, 5)。避免 "15" / "S5" 被 contains("5") 误判为 5 星
 * 3. 模糊包含：作为兜底，检测字符串里是否包含 5/4/3（对 "5星" / "⭐5" 这类中文符号组合有效）
 * 4. 最后兜底：返回 3
 *
 * 所有调用方（米哈游 API 响应解析、UIGF 导入的两处 rank_type 解析）必须统一走此函数，
 * 避免相同字符串得到不同星级的一致性 bug。
 */
fun parseRarity(value: String): Int = when {
    value.equals("S", ignoreCase = true) -> 5
    value.equals("A", ignoreCase = true) -> 4
    value.equals("B", ignoreCase = true) -> 3
    value.matches(Regex("\\d+")) -> value.toIntOrNull()?.coerceIn(3, 5) ?: 3
    value.contains("5") -> 5
    value.contains("4") -> 4
    value.contains("3") -> 3
    else -> 3
}

/**
 * 全局共享的物品类型解析器。
 * 语义：中文包含"角色"/"武器"或英文 character/weapon，其余一律走 OTHER。
 */
fun parseItemType(value: String): Int = when {
    value.contains("角色") || value.equals("character", ignoreCase = true) -> ItemType.CHARACTER.value
    value.contains("武器") || value.equals("weapon", ignoreCase = true) -> ItemType.WEAPON.value
    else -> ItemType.OTHER.value
}
