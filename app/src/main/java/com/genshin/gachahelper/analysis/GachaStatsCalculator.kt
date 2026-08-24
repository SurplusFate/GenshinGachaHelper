package com.genshin.gachahelper.analysis

import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抽卡统计计算器
 * 负责计算垫抽、保底、出金间隔等统计数据
 */
@Singleton
class GachaStatsCalculator @Inject constructor() {

    companion object {
        // 保底上限
        private const val CHARACTER_PITY_CEILING = 90
        private const val WEAPON_PITY_CEILING = 80
        private const val STANDARD_PITY_CEILING = 90
        private const val CHRONICLED_PITY_CEILING = 90

        /**
         * 常驻五星角色列表（角色池出这些 = 歪了 50/50）
         * 出这些以外的五星 = UP 角色（赢了 50/50 或大保底）
         */
        private val STANDARD_FIVE_STAR_CHARACTERS = setOf(
            "迪卢克", "琴", "刻晴", "莫娜", "七七", "提纳里", "迪希雅"
        )
    }

    /**
     * 计算单卡池统计数据
     * @param records 该卡池的所有记录（按时间倒序排列，最新在前）
     * @param poolType 卡池类型
     * @param upItems UP 物品名称列表（用于计算 UP 率）
     */
    fun calculatePoolStats(
        records: List<GachaRecordEntity>,
        poolType: Int,
        upItems: List<String> = emptyList()
    ): PoolStats {
        // orderNumber 是 String，必须转 Long 排序，否则字典序错误（"999" > "2376"）
        val sortedRecords = records.sortedByDescending { it.orderNumber.toLongOrNull() ?: 0L }
        val poolName = GachaType.fromValue(poolType).displayName
        val pityCeiling = getPityCeiling(poolType)

        // 基础计数
        val totalPulls = sortedRecords.size
        val fiveStarRecords = sortedRecords.filter { it.rarity == 5 }
        val fourStarCount = sortedRecords.count { it.rarity == 4 }
        val threeStarCount = sortedRecords.count { it.rarity == 3 }

        // 当前垫抽：从最新记录向前找最近一次五星
        val currentPity = calculateCurrentPity(sortedRecords)

        // 最近一个五星
        val lastFiveStar = fiveStarRecords.firstOrNull()

        // 五星间隔计算
        val intervals = calculateFiveStarIntervals(sortedRecords)
        val avgPulls = if (fiveStarRecords.isNotEmpty()) {
            totalPulls.toDouble() / fiveStarRecords.size
        } else {
            0.0
        }

        val minPulls = intervals.minOrNull() ?: 0
        val maxPulls = intervals.maxOrNull() ?: 0

        // UP 率计算：角色池中，非常驻五星 = UP 角色（赢了 50/50 或大保底）
        val upFiveStars = if (poolType == GachaType.CHARACTER.value) {
            fiveStarRecords.count { it.itemName !in STANDARD_FIVE_STAR_CHARACTERS }
        } else {
            fiveStarRecords.size
        }
        val upRate = if (fiveStarRecords.isNotEmpty()) {
            upFiveStars.toDouble() / fiveStarRecords.size
        } else {
            0.0
        }

        return PoolStats(
            poolType = poolType,
            poolName = poolName,
            totalPulls = totalPulls,
            fiveStarCount = fiveStarRecords.size,
            fourStarCount = fourStarCount,
            threeStarCount = threeStarCount,
            currentPity = currentPity,
            pityCeiling = pityCeiling,
            lastFiveStarName = lastFiveStar?.itemName,
            lastFiveStarTime = lastFiveStar?.time,
            avgPullsPerFiveStar = avgPulls,
            minPullsForFiveStar = minPulls,
            maxPullsForFiveStar = maxPulls,
            fiveStarIntervals = intervals,
            upFiveStarCount = upFiveStars,
            upRate = upRate
        )
    }

    /**
     * 计算当前垫抽数
     * 从最新记录向前回溯，找到最近一次五星，计算中间的抽数
     */
    fun calculateCurrentPity(records: List<GachaRecordEntity>): Int {
        val sorted = records.sortedByDescending { it.orderNumber.toLongOrNull() ?: 0L }
        val lastFiveStarIndex = sorted.indexOfFirst { it.rarity == 5 }
        return if (lastFiveStarIndex == -1) {
            sorted.size // 从未出过五星，垫抽 = 总抽数
        } else {
            lastFiveStarIndex // 最近一次五星之后的抽数
        }
    }

    /**
     * 计算每次出金的间隔抽数列表
     * 例如：[78, 42, 90, 12] 表示每次出金分别用了 78、42、90、12 抽
     */
    fun calculateFiveStarIntervals(records: List<GachaRecordEntity>): List<Int> {
        val sorted = records.sortedBy { it.orderNumber.toLongOrNull() ?: 0L } // 正序，最老在前
        val intervals = mutableListOf<Int>()
        var lastFiveStarIndex = -1

        for ((index, record) in sorted.withIndex()) {
            if (record.rarity == 5) {
                if (lastFiveStarIndex == -1) {
                    // 第一次出金，从 0 开始算
                    intervals.add(index + 1)
                } else {
                    intervals.add(index - lastFiveStarIndex)
                }
                lastFiveStarIndex = index
            }
        }

        return intervals
    }

    /**
     * 计算距离保底还需要多少抽
     */
    fun getPullsUntilPity(poolType: Int, currentPity: Int): Int {
        val ceiling = getPityCeiling(poolType)
        return (ceiling - currentPity).coerceAtLeast(0)
    }

    /**
     * 获取卡池保底上限
     */
    fun getPityCeiling(poolType: Int): Int {
        return when (poolType) {
            GachaType.CHARACTER.value -> CHARACTER_PITY_CEILING
            GachaType.WEAPON.value -> WEAPON_PITY_CEILING
            GachaType.STANDARD.value -> STANDARD_PITY_CEILING
            GachaType.CHRONICLED.value -> CHRONICLED_PITY_CEILING
            else -> CHARACTER_PITY_CEILING
        }
    }

    /**
     * 生成完整抽卡报告
     */
    fun generateReport(
        characterRecords: List<GachaRecordEntity>,
        weaponRecords: List<GachaRecordEntity>,
        standardRecords: List<GachaRecordEntity>,
        chronicledRecords: List<GachaRecordEntity> = emptyList()
    ): GachaReport {
        val characterStats = if (characterRecords.isNotEmpty()) {
            calculatePoolStats(characterRecords, GachaType.CHARACTER.value)
        } else null

        val weaponStats = if (weaponRecords.isNotEmpty()) {
            calculatePoolStats(weaponRecords, GachaType.WEAPON.value)
        } else null

        val standardStats = if (standardRecords.isNotEmpty()) {
            calculatePoolStats(standardRecords, GachaType.STANDARD.value)
        } else null

        val chronicledStats = if (chronicledRecords.isNotEmpty()) {
            calculatePoolStats(chronicledRecords, GachaType.CHRONICLED.value)
        } else null

        val allRecords = characterRecords + weaponRecords + standardRecords + chronicledRecords
        val allFiveStars = allRecords.filter { it.rarity == 5 }
        val totalPulls = allRecords.size

        val allIntervals = buildList {
            if (characterStats != null) addAll(characterStats.fiveStarIntervals)
            if (weaponStats != null) addAll(weaponStats.fiveStarIntervals)
            if (standardStats != null) addAll(standardStats.fiveStarIntervals)
            if (chronicledStats != null) addAll(chronicledStats.fiveStarIntervals)
        }

        val avgPulls = if (allFiveStars.isNotEmpty()) {
            totalPulls.toDouble() / allFiveStars.size
        } else 0.0

        val bestLuck = allIntervals.minOrNull() ?: 0
        val worstLuck = allIntervals.maxOrNull() ?: 0

        val upRate = characterStats?.upRate ?: 0.0

        return GachaReport(
            totalPulls = totalPulls,
            totalFiveStars = allFiveStars.size,
            avgPullsPerFiveStar = avgPulls,
            bestLuck = bestLuck,
            worstLuck = worstLuck,
            upSuccessRate = upRate,
            characterPoolStats = characterStats,
            weaponPoolStats = weaponStats,
            standardPoolStats = standardStats,
            chronicledPoolStats = chronicledStats
        )
    }
}
