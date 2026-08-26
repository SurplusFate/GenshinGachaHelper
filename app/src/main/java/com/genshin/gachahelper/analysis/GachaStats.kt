package com.genshin.gachahelper.analysis

import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType

/**
 * 单卡池统计数据
 */
data class PoolStats(
    val poolType: Int,
    val poolName: String,
    val totalPulls: Int,
    val fiveStarCount: Int,
    val fourStarCount: Int,
    val threeStarCount: Int,
    val currentPity: Int,
    val pityCeiling: Int,
    val lastFiveStarName: String?,
    val lastFiveStarTime: String?,
    val avgPullsPerFiveStar: Double,
    val minPullsForFiveStar: Int,
    val maxPullsForFiveStar: Int,
    val fiveStarIntervals: List<Int>,
    val upFiveStarCount: Int,
    val upRate: Double,
    // ===== 新增：运气评分 =====
    val luckScore: Int,              // 总体运气分（0~100），无五星时为0
    val singleLuckScores: List<Int>, // 每次出金的单次运气分列表
    val luckConfidence: LuckConfidence // 统计可信度
)

/**
 * 整体抽卡报告数据
 */
data class GachaReport(
    val totalPulls: Int,
    val totalFiveStars: Int,
    val avgPullsPerFiveStar: Double,
    val bestLuck: Int,    // 最少抽数出金
    val worstLuck: Int,   // 最多抽数出金
    val upSuccessRate: Double,
    val characterPoolStats: PoolStats?,
    val character2PoolStats: PoolStats?,
    val weaponPoolStats: PoolStats?,
    val standardPoolStats: PoolStats?,
    val novicePoolStats: PoolStats?,
    val chronicledPoolStats: PoolStats?,
    // ===== 新增：综合运气 =====
    val overallLuckScore: Int,       // 综合运气分（各池加权），无有效数据时为0
    val overallLuckConfidence: LuckConfidence // 综合统计可信度
)

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
 * 运气评级（用于UI文案）
 */
object LuckVerdict {
    fun fromScore(score: Int): String = when {
        score >= 90 -> "极欧"
        score >= 80 -> "非常欧"
        score >= 70 -> "比较欧"
        score >= 60 -> "略欧"
        score >= 40 -> "正常"
        score >= 30 -> "略非"
        score >= 20 -> "比较非"
        score >= 10 -> "非常非"
        else -> "极非"
    }
}
