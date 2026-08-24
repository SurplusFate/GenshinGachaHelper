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
    val upRate: Double
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
    val weaponPoolStats: PoolStats?,
    val standardPoolStats: PoolStats?,
    val chronicledPoolStats: PoolStats?
)
