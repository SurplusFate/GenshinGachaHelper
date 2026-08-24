package com.genshin.gachahelper.data.model

/**
 * 卡池类型枚举
 */
enum class GachaType(val value: Int, val displayName: String) {
    CHARACTER(301, "角色活动祈愿"),
    WEAPON(302, "武器活动祈愿"),
    STANDARD(200, "常驻祈愿"),
    NOVICE(100, "新手祈愿");

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
