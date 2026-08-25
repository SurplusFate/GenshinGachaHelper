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

        // 角色池301和400共享保底：所有统计指标必须统一使用合并记录
        // 否则会出现 totalPulls=535 但 avgPulls=55（合并2651/48）的矛盾，用户会质疑数据不对
        val intervalRecords = if (sharedPityRecords.isNotEmpty()) {
            (records + sharedPityRecords).sortedByDescending { it.time }
        } else {
            sortedRecords
        }

        // 基础计数：共享保底池用合并记录（与垫抽/间隔/平均出金保持一致）
        val totalPulls = intervalRecords.size
        val fiveStarRecords = intervalRecords.filter { it.rarity == 5 }
        val fourStarCount = intervalRecords.count { it.rarity == 4 }
        val threeStarCount = intervalRecords.count { it.rarity == 3 }

        val currentPity = calculateCurrentPity(intervalRecords)

        // 最近一个五星（共享保底池取合并后的最近五星）
        val lastFiveStar = intervalRecords.firstOrNull { it.rarity == 5 }

        // 五星间隔计算：角色池301和400共享保底，间隔也必须合并计算
        // 否则单池间隔会超过保底上限（如 147 抽），因为另一个池的五星不算"重置保底"
        val intervals = calculateFiveStarIntervals(intervalRecords)

        // 平均出金：共享保底池用合并记录计算（否则单池均值会超 90，误导用户）
        val avgPulls = if (fiveStarRecords.isNotEmpty()) {
            intervalRecords.size.toDouble() / fiveStarRecords.size
        } else {
            0.0
        }

        // 没有五星的池：min/max 设为 0，UI 层应过滤掉 fiveStarCount == 0 的池
        val minPulls = if (intervals.isEmpty()) 0 else intervals.minOrNull() ?: 0
        val maxPulls = if (intervals.isEmpty()) 0 else intervals.maxOrNull() ?: 0

        // UP 率计算：角色池301和400共享保底且共享50/50，UP率也合并计算
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

        return PoolStats(
            poolType = poolType,
            poolName = poolName,
            totalPulls = totalPulls,
            fiveStarCount = fiveStarRecords.size,
            fourStarCount = fourStarCount,
            threeStarCount = threeStarCount,
            // 游戏机制内 currentPity < pityCeiling（90/80/20 之内必出或池关闭）。
            // 如果脏数据（导入/解析错误漏了五星）导致超出，在此强制 clamp，避免 UI 进度条 100% 卡死。
            currentPity = currentPity.coerceIn(0, pityCeiling - 1),
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
     */
    fun calculateFiveStarIntervals(records: List<GachaRecordEntity>): List<Int> {
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
        val mergedCharacterIntervals = calculateFiveStarIntervals(mergedCharacterRecords)

        val allIntervals = buildList {
            addAll(mergedCharacterIntervals)
            if (weaponStats != null) addAll(weaponStats.fiveStarIntervals)
            if (standardStats != null) addAll(standardStats.fiveStarIntervals)
            if (noviceStats != null) addAll(noviceStats.fiveStarIntervals)
            if (chronicledStats != null) addAll(chronicledStats.fiveStarIntervals)
        }

        val avgPulls = if (allFiveStars.isNotEmpty()) {
            totalPulls.toDouble() / allFiveStars.size
        } else 0.0

        val bestLuck = allIntervals.minOrNull() ?: 0
        val worstLuck = allIntervals.maxOrNull() ?: 0

        // UP 率：角色池301和400共享保底且共享50/50，两个池的 upRate 已经是合并计算的结果
        // 直接取任意一个非空池的 upRate 即可，避免双池叠加导致重复计数
        val upRate = characterStats?.upRate ?: character2Stats?.upRate ?: 0.0

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
            chronicledPoolStats = chronicledStats
        )
    }
}
