package com.genshin.gachahelper.analysis

/**
 * 卡池基础统计（独立统计，不涉及共享保底）
 *
 * 这些统计只与单个卡池自身记录相关，
 * 角色池 301 和 400 各自独立计算，不合并。
 */
data class PoolBasicStats(
    /** 总抽数 */
    val totalPulls: Int,
    /** 五星数量 */
    val fiveStarCount: Int,
    /** 四星数量 */
    val fourStarCount: Int,
    /** 三星数量 */
    val threeStarCount: Int
)

/**
 * 保底统计（可能涉及共享保底）
 *
 * 角色池 301 和 400 共享保底，因此垫抽计算需要合并两池记录。
 * 其他卡池独立计算。
 */
data class PityStats(
    /** 当前垫抽数（距离上一个五星已抽了多少抽） */
    val currentPity: Int,
    /** 保底上限（角色池 90，武器池 80，新手池 20 等） */
    val pityCeiling: Int,
    /** 距离保底还剩多少抽 */
    val pullsUntilPity: Int,
    /** 是否大保底（上一次五星歪了常驻，下次必出 UP） */
    val isGuaranteed: Boolean,
    /** 上一个五星名称 */
    val lastFiveStarName: String?,
    /** 上一个五星时间 */
    val lastFiveStarTime: String?
)

/**
 * 五星间隔统计
 *
 * 角色池 301 和 400 共享保底，因此五星间隔需要合并两池记录后计算。
 * 其他卡池独立计算。
 *
 * 重要原则：
 * - 平均出金 = 已完成五星间隔之和 ÷ 间隔数量
 * - 当前垫抽不算入平均出金（因为还没出金，不是一个完整间隔）
 * - 最欧/最非只在已完成间隔中取极值
 * - 运气分基于真实概率模型计算
 */
data class FiveStarStats(
    /** 已完成的五星间隔列表（从最早到最近） */
    val intervals: List<Int>,
    /** 平均出金抽数（已完成间隔的平均值） */
    val avgPullsPerFiveStar: Double,
    /** 最欧（最小间隔），无五星时为 0 */
    val minPulls: Int,
    /** 最非（最大间隔），无五星时为 0 */
    val maxPulls: Int,
    /** 总体运气分（0~100），无五星时为 0 */
    val luckScore: Int,
    /** 每次出金的单次运气分列表（与 intervals 一一对应） */
    val singleLuckScores: List<Int>,
    /** 统计可信度 */
    val luckConfidence: LuckConfidence
) {
    companion object {
        val EMPTY = FiveStarStats(
            intervals = emptyList(),
            avgPullsPerFiveStar = 0.0,
            minPulls = 0,
            maxPulls = 0,
            luckScore = 0,
            singleLuckScores = emptyList(),
            luckConfidence = LuckConfidence.INSUFFICIENT
        )
    }
}

/**
 * 统计可信度等级
 *
 * 根据有效五星间隔数量判定：
 * - 1~3 次：数据较少
 * - 4~9 次：参考
 * - 10~19 次：较可靠
 * - 20 次及以上：可靠
 */
enum class LuckConfidence(val displayName: String, val order: Int) {
    INSUFFICIENT("数据较少", 0),
    LOW("参考", 1),
    MEDIUM("较可靠", 2),
    HIGH("可靠", 3);

    companion object {
        fun fromSampleCount(count: Int): LuckConfidence {
            return when {
                count <= 0 -> INSUFFICIENT
                count <= 3 -> INSUFFICIENT
                count <= 9 -> LOW
                count <= 19 -> MEDIUM
                else -> HIGH
            }
        }
    }
}

/**
 * UP 统计
 *
 * 角色池 301 和 400 的 UP 状态共享（50/50 连续性）。
 * UP 判断依赖卡池 + 抽卡时间 + 当期 UP 物品。
 *
 * 当 upItems 为空时，使用常驻五星列表作为兜底判断：
 * - 非常驻五星 → 视为 UP
 * - 常驻五星 → 视为歪了
 * 这是近似判断，精度低于精确 UP 表匹配。
 */
data class UpStats(
    /** UP 五星数量 */
    val upFiveStarCount: Int,
    /** 歪的五星数量（常驻五星） */
    val lostFiveStarCount: Int,
    /** UP 成功率（0.0 ~ 1.0），无五星时为 0.0 */
    val upRate: Double
) {
    companion object {
        val EMPTY = UpStats(
            upFiveStarCount = 0,
            lostFiveStarCount = 0,
            upRate = 0.0
        )
    }
}

/**
 * 单卡池完整统计数据
 *
 * 角色池 301 和 400 的特殊处理：
 * - basic（基础计数）：独立
 * - pity（垫抽）：共享（合并计算）
 * - fiveStar（间隔）：共享（合并计算）
 * - up（UP 率）：独立计算各自池的 UP，但 50/50 状态共享
 */
data class PoolStats(
    /** 卡池类型 */
    val poolType: Int,
    /** 卡池名称 */
    val poolName: String,
    /** 基础统计（独立） */
    val basic: PoolBasicStats,
    /** 保底统计（角色池共享） */
    val pity: PityStats,
    /** 五星间隔统计（角色池共享） */
    val fiveStar: FiveStarStats,
    /** UP 统计（独立计数，50/50 状态共享） */
    val up: UpStats
) {
    // 便捷属性：保持与旧版 API 兼容，减少外部改动
    val totalPulls: Int get() = basic.totalPulls
    val fiveStarCount: Int get() = basic.fiveStarCount
    val fourStarCount: Int get() = basic.fourStarCount
    val threeStarCount: Int get() = basic.threeStarCount
    val currentPity: Int get() = pity.currentPity
    val pityCeiling: Int get() = pity.pityCeiling
    val lastFiveStarName: String? get() = pity.lastFiveStarName
    val lastFiveStarTime: String? get() = pity.lastFiveStarTime
    val avgPullsPerFiveStar: Double get() = fiveStar.avgPullsPerFiveStar
    val minPullsForFiveStar: Int get() = fiveStar.minPulls
    val maxPullsForFiveStar: Int get() = fiveStar.maxPulls
    val fiveStarIntervals: List<Int> get() = fiveStar.intervals
    val upFiveStarCount: Int get() = up.upFiveStarCount
    val upRate: Double get() = up.upRate
    val luckScore: Int get() = fiveStar.luckScore
    val singleLuckScores: List<Int> get() = fiveStar.singleLuckScores
    val luckConfidence: LuckConfidence get() = fiveStar.luckConfidence
}

/**
 * 整体抽卡报告
 *
 * 聚合所有卡池的统计数据，提供全局视角。
 */
data class GachaReport(
    /** 总抽数（所有池合计） */
    val totalPulls: Int,
    /** 五星总数（所有池合计） */
    val totalFiveStars: Int,
    /** 全局平均出金（所有池已完成间隔的平均值） */
    val avgPullsPerFiveStar: Double,
    /** 全局最欧（所有池已完成间隔中的最小值） */
    val bestLuck: Int,
    /** 全局最非（所有池已完成间隔中的最大值） */
    val worstLuck: Int,
    /** 全局 UP 成功率（仅角色池） */
    val upSuccessRate: Double,
    /** 综合运气分（0~100） */
    val overallLuckScore: Int,
    /** 综合运气可信度 */
    val overallLuckConfidence: LuckConfidence,
    /** 角色活动祈愿(301)统计 */
    val characterPoolStats: PoolStats?,
    /** 角色活动祈愿-2(400)统计 */
    val character2PoolStats: PoolStats?,
    /** 武器活动祈愿(302)统计 */
    val weaponPoolStats: PoolStats?,
    /** 常驻祈愿(200)统计 */
    val standardPoolStats: PoolStats?,
    /** 新手祈愿(100)统计 */
    val novicePoolStats: PoolStats?,
    /** 集录祈愿(800)统计 */
    val chronicledPoolStats: PoolStats?
)
