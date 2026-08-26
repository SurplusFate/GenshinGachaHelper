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
        // 保底上限（与卡池枚举 GachaType.value 对应：
        // 角色池(301) / 角色池-2(400) / 常驻(200) / 集录(800)：90 抽五星保底
        // 武器神铸赋形(302)：80 抽五星保底
        // 新手祈愿(100)：无五星保底，上限仅 20 抽，抽满自动关闭；此处 pityCeiling = 20
        //   是「池总抽数」而不是 X 抽必出五星的阈值，UI 层会特殊处理新手池的文案。
        private const val CHARACTER_PITY_CEILING = 90
        private const val CHARACTER_2_PITY_CEILING = 90
        private const val WEAPON_PITY_CEILING = 80
        private const val STANDARD_PITY_CEILING = 90
        private const val NOVICE_PITY_CEILING = 20
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
        upItems: List<String> = emptyList(),
        sharedPityRecords: List<GachaRecordEntity> = emptyList()
    ): PoolStats {
        // 按 time 排序：orderNumber 是按池独立编号的，合并 301+400 池时按 orderNumber 排序会错乱
        val sortedRecords = records.sortedByDescending { it.time }
        val poolName = GachaType.fromValue(poolType).displayName
        val pityCeiling = getPityCeiling(poolType)

        // 基础计数：用单池记录（与历史页记录数一致，用户可对照）
        val totalPulls = sortedRecords.size
        val fiveStarRecords = sortedRecords.filter { it.rarity == 5 }
        val fourStarCount = sortedRecords.count { it.rarity == 4 }
        val threeStarCount = sortedRecords.count { it.rarity == 3 }

        // 保底相关计算：角色池301和400共享保底，用合并记录计算
        val intervalRecords = if (sharedPityRecords.isNotEmpty()) {
            (records + sharedPityRecords).sortedByDescending { it.time }
        } else {
            sortedRecords
        }

        val currentPity = calculateCurrentPity(intervalRecords)

        // 最近一个五星（共享保底池取合并后的最近五星）
        val lastFiveStar = intervalRecords.firstOrNull { it.rarity == 5 }

        // 五星间隔计算：角色池301和400共享保底，间隔也必须合并计算
        // 过滤超过保底上限的不可能值（数据不完整时第一条间隔可能 > 90）
        val intervals = calculateFiveStarIntervals(intervalRecords, pityCeiling)

        // ===== 运气评分（基于理论概率模型） =====
        // 单次运气分：每个完整五星间隔对应一个评分
        val singleLuckScores = intervals.map { interval ->
            GachaProbabilityModel.calculateSingleLuckScore(interval, poolType)
        }
        // 总体运气分：所有单次运气分的算术平均
        val luckScore = if (singleLuckScores.isNotEmpty()) {
            singleLuckScores.average().toInt().coerceIn(0, 100)
        } else {
            0
        }
        // 统计可信度：基于有效五星间隔数量
        val luckConfidence = LuckConfidence.fromSampleCount(intervals.size)

        // 平均出金：共享保底池用合并记录计算（否则单池均值会超 90，误导用户）
        val mergedFiveStars = intervalRecords.filter { it.rarity == 5 }
        val avgPulls = if (mergedFiveStars.isNotEmpty()) {
            intervalRecords.size.toDouble() / mergedFiveStars.size
        } else {
            0.0
        }

        // 没有五星的池：min/max 设为 0，UI 层应过滤掉 fiveStarCount == 0 的池
        val minPulls = if (intervals.isEmpty()) 0 else intervals.minOrNull() ?: 0
        val maxPulls = if (intervals.isEmpty()) 0 else intervals.maxOrNull() ?: 0

        // UP 率计算：用单池五星记录（UP 角色因池而异）
        // 非常驻五星 = UP 角色（赢了 50/50 或大保底）
        val upFiveStars = if (poolType == GachaType.CHARACTER.value || poolType == GachaType.CHARACTER_2.value) {
            fiveStarRecords.count { it.itemName !in STANDARD_FIVE_STAR_CHARACTERS }
        } else {
            fiveStarRecords.size
        }
        val upRate = if (fiveStarRecords.isNotEmpty()) {
            upFiveStars.toDouble() / fiveStarRecords.size
        } else {
            0.0
        }

        // 新手池：pityCeiling=20 是「池总抽数」，currentPity 可以等于 pityCeiling（20/20=池关闭）
        // 其他池：currentPity < pityCeiling（90/80 之内必出五星），clamp 到 pityCeiling-1
        val maxPity = if (poolType == GachaType.NOVICE.value) pityCeiling else pityCeiling - 1
        return PoolStats(
            poolType = poolType,
            poolName = poolName,
            totalPulls = totalPulls,
            fiveStarCount = fiveStarRecords.size,
            fourStarCount = fourStarCount,
            threeStarCount = threeStarCount,
            currentPity = currentPity.coerceIn(0, maxPity),
            pityCeiling = pityCeiling,
            lastFiveStarName = lastFiveStar?.itemName,
            lastFiveStarTime = lastFiveStar?.time,
            avgPullsPerFiveStar = avgPulls,
            minPullsForFiveStar = minPulls,
            maxPullsForFiveStar = maxPulls,
            fiveStarIntervals = intervals,
            upFiveStarCount = upFiveStars,
            upRate = upRate,
            luckScore = luckScore,
            singleLuckScores = singleLuckScores,
            luckConfidence = luckConfidence
        )
    }

    /**
     * 计算当前垫抽数
     * 从最新记录向前回溯，找到最近一次五星，计算中间的抽数
     */
    fun calculateCurrentPity(records: List<GachaRecordEntity>): Int {
        val sorted = records.sortedByDescending { it.time }
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
     * @param pityCeiling 保底上限，超过此值的间隔会被过滤（数据不完整导致的不可能值）
     */
    fun calculateFiveStarIntervals(records: List<GachaRecordEntity>, pityCeiling: Int = 0): List<Int> {
        val sorted = records.sortedBy { it.time } // 正序，最老在前
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

        // 过滤超过保底上限的不可能值（数据不完整时，缺少的五星会导致间隔 > 90）
        return if (pityCeiling > 0) intervals.filter { it <= pityCeiling } else intervals
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
            GachaType.CHARACTER_2.value -> CHARACTER_2_PITY_CEILING
            GachaType.WEAPON.value -> WEAPON_PITY_CEILING
            GachaType.STANDARD.value -> STANDARD_PITY_CEILING
            GachaType.NOVICE.value -> NOVICE_PITY_CEILING
            GachaType.CHRONICLED.value -> CHRONICLED_PITY_CEILING
            else -> CHARACTER_PITY_CEILING
        }
    }

    /**
     * 生成完整抽卡报告
     */
    fun generateReport(
        characterRecords: List<GachaRecordEntity>,
        character2Records: List<GachaRecordEntity> = emptyList(),
        weaponRecords: List<GachaRecordEntity>,
        standardRecords: List<GachaRecordEntity>,
        noviceRecords: List<GachaRecordEntity> = emptyList(),
        chronicledRecords: List<GachaRecordEntity> = emptyList()
    ): GachaReport {
        // 角色池301和400共享保底，计算时互相传入对方的记录
        val characterStats = if (characterRecords.isNotEmpty()) {
            calculatePoolStats(
                characterRecords, GachaType.CHARACTER.value,
                sharedPityRecords = character2Records
            )
        } else null

        val character2Stats = if (character2Records.isNotEmpty()) {
            calculatePoolStats(
                character2Records, GachaType.CHARACTER_2.value,
                sharedPityRecords = characterRecords
            )
        } else null

        val weaponStats = if (weaponRecords.isNotEmpty()) {
            calculatePoolStats(weaponRecords, GachaType.WEAPON.value)
        } else null

        val standardStats = if (standardRecords.isNotEmpty()) {
            calculatePoolStats(standardRecords, GachaType.STANDARD.value)
        } else null

        val noviceStats = if (noviceRecords.isNotEmpty()) {
            calculatePoolStats(noviceRecords, GachaType.NOVICE.value)
        } else null

        val chronicledStats = if (chronicledRecords.isNotEmpty()) {
            calculatePoolStats(chronicledRecords, GachaType.CHRONICLED.value)
        } else null

        // 所有记录
        val allRecords = characterRecords + character2Records + weaponRecords +
            standardRecords + noviceRecords + chronicledRecords
        val allFiveStars = allRecords.filter { it.rarity == 5 }
        val totalPulls = allRecords.size

        // 五星间隔：角色池301和400共享保底，必须合并后计算间隔
        // 否则会出现184等超过保底上限(90)的不合理间隔
        val mergedCharacterRecords = characterRecords + character2Records
        val mergedCharacterIntervals = calculateFiveStarIntervals(mergedCharacterRecords, CHARACTER_PITY_CEILING)

        val allIntervals = buildList {
            addAll(mergedCharacterIntervals)
            if (weaponStats != null) addAll(weaponStats.fiveStarIntervals)
            if (standardStats != null) addAll(standardStats.fiveStarIntervals)
            if (noviceStats != null) addAll(noviceStats.fiveStarIntervals)
            if (chronicledStats != null) addAll(chronicledStats.fiveStarIntervals)
        }

        // 平均出金：使用已完成的五星间隔均值（不含当前垫抽，更准确反映真实出金水平）
        val avgPulls = if (allIntervals.isNotEmpty()) {
            allIntervals.average()
        } else 0.0

        val bestLuck = allIntervals.minOrNull() ?: 0
        val worstLuck = allIntervals.maxOrNull() ?: 0

        // UP 率：综合角色池和角色池-2（各池单池五星分别计算后汇总）
        val totalUpFiveStars = (characterStats?.upFiveStarCount ?: 0) + (character2Stats?.upFiveStarCount ?: 0)
        val totalCharFiveStars = (characterStats?.fiveStarCount ?: 0) + (character2Stats?.fiveStarCount ?: 0)
        val upRate = if (totalCharFiveStars > 0) {
            totalUpFiveStars.toDouble() / totalCharFiveStars
        } else 0.0

        // ===== 综合运气分 =====
        // 角色池301和400共享保底且共享同一份间隔数据，只取一个池的运气分（避免重复）
        val charLuckStats = characterStats ?: character2Stats
        val poolStatsWithLuck = listOfNotNull(
            charLuckStats,
            weaponStats,
            standardStats,
            chronicledStats
        ).filter { it.fiveStarIntervals.isNotEmpty() }

        val (overallLuckScore, overallLuckConfidence) = if (poolStatsWithLuck.isEmpty()) {
            0 to LuckConfidence.INSUFFICIENT
        } else {
            // 按有效样本数加权平均
            val totalSamples = poolStatsWithLuck.sumOf { it.fiveStarIntervals.size }
            val weightedScore = poolStatsWithLuck.sumOf {
                it.luckScore * it.fiveStarIntervals.size
            }.toDouble() / totalSamples
            weightedScore.toInt().coerceIn(0, 100) to LuckConfidence.fromSampleCount(totalSamples)
        }

        return GachaReport(
            totalPulls = totalPulls,
            totalFiveStars = allFiveStars.size,
            avgPullsPerFiveStar = avgPulls,
            bestLuck = bestLuck,
            worstLuck = worstLuck,
            upSuccessRate = upRate,
            characterPoolStats = characterStats,
            character2PoolStats = character2Stats,
            weaponPoolStats = weaponStats,
            standardPoolStats = standardStats,
            novicePoolStats = noviceStats,
            chronicledPoolStats = chronicledStats,
            overallLuckScore = overallLuckScore,
            overallLuckConfidence = overallLuckConfidence
        )
    }
}
