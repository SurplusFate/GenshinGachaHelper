package com.genshin.gachahelper.analysis

import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抽卡统计计算器
 *
 * 核心原则：
 * 1. 排序基准：按 orderNumber 升序（抽卡先后顺序）。time 只有秒级精度，同一秒多抽会错乱。
 * 2. 角色池 301/400 共享保底：垫抽、五星间隔、50/50 状态需要合并两池记录计算。
 * 3. 基础计数（总抽数、各星级数量）：各池独立统计。
 * 4. 平均出金 = 已完成五星间隔之和 ÷ 间隔数量（当前垫抽不计入）。
 * 5. 不使用 coerceIn 隐藏异常——数据有问题就让它暴露出来。
 */
@Singleton
class GachaStatsCalculator @Inject constructor() {

    companion object {
        // 保底上限
        private const val CHARACTER_PITY_CEILING = 90
        private const val CHARACTER_2_PITY_CEILING = 90
        private const val WEAPON_PITY_CEILING = 80
        private const val STANDARD_PITY_CEILING = 90
        private const val NOVICE_PITY_CEILING = 20
        private const val CHRONICLED_PITY_CEILING = 90

        /**
         * 常驻五星角色列表（兜底 UP 判断用）
         * 非常驻 → 视为 UP；常驻 → 视为歪了
         * 注意：这是近似判断，精确判断需要卡池 UP 表。
         */
        private val STANDARD_FIVE_STAR_CHARACTERS = setOf(
            "迪卢克", "琴", "刻晴", "莫娜", "七七", "提纳里", "迪希雅"
        )
    }

    // ==================== 对外主接口 ====================

    /**
     * 计算单个卡池的统计数据。
     *
     * @param records 该卡池的所有记录
     * @param poolType 卡池类型
     * @param upItems 当前池的 UP 物品列表（用于精确 UP 判断），为空时用常驻列表兜底
     * @param sharedPityRecords 共享保底的另一个池的记录（角色池 301↔400 时传）
     */
    fun calculatePoolStats(
        records: List<GachaRecordEntity>,
        poolType: Int,
        upItems: List<String> = emptyList(),
        sharedPityRecords: List<GachaRecordEntity> = emptyList()
    ): PoolStats {
        val poolName = GachaType.fromValue(poolType).displayName
        val pityCeiling = getPityCeiling(poolType)

        // 按 orderNumber 升序排列（抽卡先后顺序）
        val sortedRecords = sortByOrder(records)

        // ---- 1. 基础统计（独立） ----
        val basic = calculateBasicStats(sortedRecords)

        // ---- 2. 保底 & 五星间隔（可能共享） ----
        val mergedForShared = if (sharedPityRecords.isNotEmpty()) {
            sortByOrder(records + sharedPityRecords)
        } else {
            sortedRecords
        }

        val pity = calculatePityStats(
            records = mergedForShared,
            poolType = poolType,
            pityCeiling = pityCeiling,
            upItems = upItems
        )

        val fiveStar = calculateFiveStarStats(mergedForShared)

        // ---- 3. UP 统计（独立计数） ----
        val up = calculateUpStats(sortedRecords, upItems)

        return PoolStats(
            poolType = poolType,
            poolName = poolName,
            basic = basic,
            pity = pity,
            fiveStar = fiveStar,
            up = up
        )
    }

    /**
     * 生成完整抽卡报告。
     *
     * 角色池 301 和 400 共享保底，互相传入 sharedPityRecords。
     */
    fun generateReport(
        characterRecords: List<GachaRecordEntity>,
        character2Records: List<GachaRecordEntity> = emptyList(),
        weaponRecords: List<GachaRecordEntity>,
        standardRecords: List<GachaRecordEntity>,
        noviceRecords: List<GachaRecordEntity> = emptyList(),
        chronicledRecords: List<GachaRecordEntity> = emptyList()
    ): GachaReport {
        // 角色池 301 和 400 共享保底，互相传入对方记录
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

        // ---- 全局汇总 ----
        val allPoolStats = listOfNotNull(
            characterStats, character2Stats, weaponStats,
            standardStats, noviceStats, chronicledStats
        )

        val totalPulls = allPoolStats.sumOf { it.basic.totalPulls }
        val totalFiveStars = allPoolStats.sumOf { it.basic.fiveStarCount }

        // 全局平均出金：所有池的已完成间隔合并后取平均
        val allIntervals = allPoolStats.flatMap { it.fiveStar.intervals }
        val avgPulls = if (allIntervals.isNotEmpty()) {
            allIntervals.average()
        } else 0.0

        val bestLuck = allIntervals.minOrNull() ?: 0
        val worstLuck = allIntervals.maxOrNull() ?: 0

        // 全局 UP 率：仅角色池
        val totalUpFiveStars = (characterStats?.up?.upFiveStarCount ?: 0) +
                (character2Stats?.up?.upFiveStarCount ?: 0)
        val totalCharFiveStars = (characterStats?.basic?.fiveStarCount ?: 0) +
                (character2Stats?.basic?.fiveStarCount ?: 0)
        val upRate = if (totalCharFiveStars > 0) {
            totalUpFiveStars.toDouble() / totalCharFiveStars
        } else 0.0

        return GachaReport(
            totalPulls = totalPulls,
            totalFiveStars = totalFiveStars,
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

    // ==================== 内部计算方法 ====================

    /**
     * 按 orderNumber 升序排列（抽卡先后顺序，最老在前）。
     * orderNumber 是全局递增的字符串数字，按字典序排序即可（因为长度相同）。
     */
    private fun sortByOrder(records: List<GachaRecordEntity>): List<GachaRecordEntity> {
        return records.sortedBy { it.orderNumber }
    }

    /**
     * 按 orderNumber 降序排列（最新在前）。
     */
    private fun sortByOrderDesc(records: List<GachaRecordEntity>): List<GachaRecordEntity> {
        return records.sortedByDescending { it.orderNumber }
    }

    /**
     * 计算基础统计（总抽数、各星级数量）。
     * 输入：按 orderNumber 升序排列的记录。
     */
    private fun calculateBasicStats(
        sortedRecords: List<GachaRecordEntity>
    ): PoolBasicStats {
        var fiveCount = 0
        var fourCount = 0
        var threeCount = 0
        for (r in sortedRecords) {
            when (r.rarity) {
                5 -> fiveCount++
                4 -> fourCount++
                3 -> threeCount++
            }
        }
        return PoolBasicStats(
            totalPulls = sortedRecords.size,
            fiveStarCount = fiveCount,
            fourStarCount = fourCount,
            threeStarCount = threeCount
        )
    }

    /**
     * 计算保底统计（垫抽、是否大保底、上一个五星等）。
     *
     * 对于角色池 301/400，传入合并后的记录。
     * 输入：按 orderNumber 升序排列的记录。
     */
    private fun calculatePityStats(
        records: List<GachaRecordEntity>,
        poolType: Int,
        pityCeiling: Int,
        upItems: List<String>
    ): PityStats {
        if (records.isEmpty()) {
            return PityStats(
                currentPity = 0,
                pityCeiling = pityCeiling,
                pullsUntilPity = pityCeiling,
                isGuaranteed = false,
                lastFiveStarName = null,
                lastFiveStarTime = null
            )
        }

        // 从最新记录往前找，找到最近一个五星
        val descRecords = sortByOrderDesc(records)
        var pity = 0
        var lastFiveStar: GachaRecordEntity? = null

        for (r in descRecords) {
            if (r.rarity == 5) {
                lastFiveStar = r
                break
            }
            pity++
        }

        // 如果没有五星，垫抽 = 总抽数
        if (lastFiveStar == null) {
            pity = records.size
        }

        // 判断是否大保底：看最近一次五星是不是 UP
        val isGuaranteed = if (lastFiveStar != null) {
            // 最近一次是常驻（歪了）→ 下次必出 UP（大保底）
            !isUpItem(lastFiveStar.itemName, upItems, poolType)
        } else {
            // 从未出过五星 → 不是大保底（第一次是 50/50）
            false
        }

        val pullsUntil = (pityCeiling - pity).coerceAtLeast(0)

        return PityStats(
            currentPity = pity,
            pityCeiling = pityCeiling,
            pullsUntilPity = pullsUntil,
            isGuaranteed = isGuaranteed,
            lastFiveStarName = lastFiveStar?.itemName,
            lastFiveStarTime = lastFiveStar?.time
        )
    }

    /**
     * 计算五星间隔统计。
     *
     * 平均出金 = 已完成间隔之和 ÷ 间隔数量
     * 当前垫抽不计入平均（因为还没出金，不是完整间隔）。
     *
     * 输入：按 orderNumber 升序排列的记录。
     */
    private fun calculateFiveStarStats(
        records: List<GachaRecordEntity>
    ): FiveStarStats {
        if (records.isEmpty()) {
            return FiveStarStats.EMPTY
        }

        val intervals = mutableListOf<Int>()
        var lastFiveStarIndex = -1
        var pullNumber = 0

        for (record in records) {
            pullNumber++
            if (record.rarity == 5) {
                val interval = if (lastFiveStarIndex == -1) {
                    pullNumber  // 第一个五星
                } else {
                    pullNumber - lastFiveStarIndex
                }
                intervals.add(interval)
                lastFiveStarIndex = pullNumber
            }
        }

        if (intervals.isEmpty()) {
            return FiveStarStats.EMPTY
        }

        val avg = intervals.average()
        val min = intervals.minOrNull() ?: 0
        val max = intervals.maxOrNull() ?: 0

        return FiveStarStats(
            intervals = intervals,
            avgPullsPerFiveStar = avg,
            minPulls = min,
            maxPulls = max
        )
    }

    /**
     * 计算 UP 统计。
     *
     * @param sortedRecords 按 orderNumber 升序排列的单池记录
     * @param upItems UP 物品列表，为空时用常驻五星列表兜底
     */
    private fun calculateUpStats(
        sortedRecords: List<GachaRecordEntity>,
        upItems: List<String>
    ): UpStats {
        val fiveStars = sortedRecords.filter { it.rarity == 5 }
        if (fiveStars.isEmpty()) {
            return UpStats.EMPTY
        }

        // 判断池类型：只有角色池和武器池有 UP 概念
        val poolType = sortedRecords.firstOrNull()?.poolType ?: 0
        val isCharacterPool = poolType == GachaType.CHARACTER.value ||
                poolType == GachaType.CHARACTER_2.value

        var upCount = 0
        var lostCount = 0

        for (r in fiveStars) {
            if (isUpItem(r.itemName, upItems, poolType)) {
                upCount++
            } else {
                lostCount++
            }
        }

        val rate = if (fiveStars.isNotEmpty()) {
            upCount.toDouble() / fiveStars.size
        } else 0.0

        return UpStats(
            upFiveStarCount = upCount,
            lostFiveStarCount = lostCount,
            upRate = rate
        )
    }

    /**
     * 判断一个五星物品是否为 UP。
     *
     * 优先级：
     * 1. 如果 upItems 非空，精确匹配 upItems 列表
     * 2. 否则对于角色池，用常驻列表兜底（非常驻=UP）
     * 3. 武器池等没有常驻列表的，默认全部视为 UP（不准确，但比全算歪好）
     */
    private fun isUpItem(itemName: String, upItems: List<String>, poolType: Int): Boolean {
        if (upItems.isNotEmpty()) {
            return itemName in upItems
        }

        // 兜底：角色池用常驻列表判断
        val isCharacterPool = poolType == GachaType.CHARACTER.value ||
                poolType == GachaType.CHARACTER_2.value
        if (isCharacterPool) {
            // 非常驻五星 → 视为 UP
            return itemName !in STANDARD_FIVE_STAR_CHARACTERS
        }

        // 武器池等：没有常驻列表兜底，默认不算 UP
        return false
    }

    // ==================== 公开工具方法 ====================

    /**
     * 获取卡池保底上限。
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
     * 计算当前垫抽数（公开方法，供外部直接调用）。
     * 输入任意顺序的记录列表，内部自动排序。
     */
    fun calculateCurrentPity(records: List<GachaRecordEntity>): Int {
        if (records.isEmpty()) return 0
        val desc = sortByOrderDesc(records)
        var pity = 0
        for (r in desc) {
            if (r.rarity == 5) return pity
            pity++
        }
        return pity  // 没有五星，垫抽 = 总抽数
    }

    /**
     * 计算五星间隔列表（公开方法，供外部直接调用）。
     * 输入任意顺序的记录列表，内部自动排序。
     */
    fun calculateFiveStarIntervals(records: List<GachaRecordEntity>): List<Int> {
        if (records.isEmpty()) return emptyList()
        return calculateFiveStarStats(records).intervals
    }

    /**
     * 计算距离保底还需要多少抽。
     */
    fun getPullsUntilPity(poolType: Int, currentPity: Int): Int {
        val ceiling = getPityCeiling(poolType)
        return (ceiling - currentPity).coerceAtLeast(0)
    }
}
