package com.genshin.gachahelper.analysis

import com.genshin.gachahelper.data.local.entity.GachaRecordEntity
import com.genshin.gachahelper.data.model.GachaType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * GachaStatsCalculator 单元测试
 *
 * 覆盖所有核心计算逻辑：
 * - 基础统计（总抽数、各星级数量）
 * - 当前垫抽
 * - 五星间隔
 * - 平均出金
 * - 最欧/最非
 * - 301/400 共享保底
 * - 301/400 共享五星间隔
 * - 301/400 独立总抽数
 * - 50/50 状态
 * - 各池独立性（武器、常驻等）
 * - 边界情况（空数据、无五星、只有一个五星等）
 */
class GachaStatsCalculatorTest {

    private lateinit var calculator: GachaStatsCalculator

    @Before
    fun setup() {
        calculator = GachaStatsCalculator()
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建一条抽卡记录，用于测试。
     * orderNumber 用 20 位字符串，保证字典序 = 数值序。
     */
    private fun makeRecord(
        orderNumber: Long,
        rarity: Int,
        poolType: Int = GachaType.CHARACTER.value,
        itemName: String = "测试物品",
        time: String = "2024-01-01 00:00:00"
    ): GachaRecordEntity {
        return GachaRecordEntity(
            id = 0,
            accountId = 1,
            poolType = poolType,
            itemName = itemName,
            itemType = 1,
            rarity = rarity,
            time = time,
            orderNumber = orderNumber.toString().padStart(20, '0')
        )
    }

    /**
     * 构建多条连续记录，指定哪些抽是五星。
     * @param count 总抽数
     * @param fiveStarPulls 第 X 抽是五星（从 1 开始计数）
     * @param poolType 卡池类型
     * @param startOrder 起始 orderNumber
     */
    private fun buildRecords(
        count: Int,
        fiveStarPulls: List<Int> = emptyList(),
        poolType: Int = GachaType.CHARACTER.value,
        startOrder: Long = 1000000000000000000L,
        nameByPull: Map<Int, String> = emptyMap()
    ): List<GachaRecordEntity> {
        val records = mutableListOf<GachaRecordEntity>()
        for (i in 1..count) {
            val isFiveStar = i in fiveStarPulls
            val rarity = if (isFiveStar) 5 else 3
            val name = nameByPull[i] ?: if (isFiveStar) "五星角色${i}" else "三星物品${i}"
            records.add(
                makeRecord(
                    orderNumber = startOrder + i,
                    rarity = rarity,
                    poolType = poolType,
                    itemName = name
                )
            )
        }
        return records
    }

    // ==================== 测试1：空数据 ====================

    @Test
    fun `空数据 - 所有统计为0`() {
        val result = calculator.calculatePoolStats(
            records = emptyList(),
            poolType = GachaType.CHARACTER.value
        )

        assertEquals(0, result.basic.totalPulls)
        assertEquals(0, result.basic.fiveStarCount)
        assertEquals(0, result.basic.fourStarCount)
        assertEquals(0, result.basic.threeStarCount)
        assertEquals(0, result.pity.currentPity)
        assertTrue(result.fiveStar.intervals.isEmpty())
        assertEquals(0.0, result.fiveStar.avgPullsPerFiveStar, 0.01)
        assertEquals(0, result.fiveStar.minPulls)
        assertEquals(0, result.fiveStar.maxPulls)
    }

    // ==================== 测试2：第一次五星 ====================

    @Test
    fun `第一次五星 - 第10抽出金`() {
        val records = buildRecords(count = 10, fiveStarPulls = listOf(10))
        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(10, result.basic.totalPulls)
        assertEquals(1, result.basic.fiveStarCount)
        assertEquals(10, result.fiveStar.intervals[0])
        assertEquals(1, result.fiveStar.intervals.size)
        assertEquals(10.0, result.fiveStar.avgPullsPerFiveStar, 0.01)
        // 当前垫抽：第10抽出了五星，之后没有抽了，垫抽=0
        assertEquals(0, result.pity.currentPity)
    }

    // ==================== 测试3：连续五星 ====================

    @Test
    fun `连续五星 - 第10和20抽出金`() {
        val records = buildRecords(count = 20, fiveStarPulls = listOf(10, 20))
        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(20, result.basic.totalPulls)
        assertEquals(2, result.basic.fiveStarCount)

        // 间隔：10, 10
        assertEquals(listOf(10, 10), result.fiveStar.intervals)
        assertEquals(10.0, result.fiveStar.avgPullsPerFiveStar, 0.01)
        assertEquals(10, result.fiveStar.minPulls)
        assertEquals(10, result.fiveStar.maxPulls)

        // 当前垫抽：0（最后一抽是五星）
        assertEquals(0, result.pity.currentPity)
    }

    // ==================== 测试4：当前垫抽 ====================

    @Test
    fun `当前垫抽 - 第10抽五星后继续59抽未出金`() {
        // 第10抽五星，然后第11~69抽（59抽）没出金 → 垫抽59
        val records = buildRecords(count = 69, fiveStarPulls = listOf(10))
        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(69, result.basic.totalPulls)
        assertEquals(1, result.basic.fiveStarCount)

        // 只有一个已完成间隔：10
        assertEquals(listOf(10), result.fiveStar.intervals)
        assertEquals(10.0, result.fiveStar.avgPullsPerFiveStar, 0.01)
        assertEquals(10, result.fiveStar.minPulls)
        assertEquals(10, result.fiveStar.maxPulls)

        // 当前垫抽 = 59（不能算进平均出金）
        assertEquals(59, result.pity.currentPity)
    }

    @Test
    fun `无五星时垫抽等于总抽数`() {
        val records = buildRecords(count = 50, fiveStarPulls = emptyList())
        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(50, result.basic.totalPulls)
        assertEquals(0, result.basic.fiveStarCount)
        assertEquals(50, result.pity.currentPity)
        assertTrue(result.fiveStar.intervals.isEmpty())
    }

    // ==================== 测试5：平均出金（不含当前垫抽） ====================

    @Test
    fun `平均出金 - 不包含当前垫抽`() {
        // 第10抽五星，第20抽五星，然后继续80抽没出金
        // 已完成间隔：10, 10 → 平均 10
        // 当前垫抽：80（不计入平均）
        val records = buildRecords(count = 100, fiveStarPulls = listOf(10, 20))
        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(100, result.basic.totalPulls)
        assertEquals(2, result.basic.fiveStarCount)

        // 已完成间隔：10, 10
        assertEquals(listOf(10, 10), result.fiveStar.intervals)
        assertEquals(10.0, result.fiveStar.avgPullsPerFiveStar, 0.01)

        // 当前垫抽：80
        assertEquals(80, result.pity.currentPity)

        // 最欧/最非都是 10（只有已完成间隔参与）
        assertEquals(10, result.fiveStar.minPulls)
        assertEquals(10, result.fiveStar.maxPulls)
    }

    // ==================== 测试6：最欧/最非 ====================

    @Test
    fun `最欧最非 - 间隔10、78、45`() {
        // 第10抽、第88抽、第133抽出金
        // 间隔：10, 78, 45
        val records = buildRecords(count = 133, fiveStarPulls = listOf(10, 88, 133))
        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(listOf(10, 78, 45), result.fiveStar.intervals)
        assertEquals(10, result.fiveStar.minPulls)  // 最欧
        assertEquals(78, result.fiveStar.maxPulls)  // 最非
        assertEquals((10 + 78 + 45) / 3.0, result.fiveStar.avgPullsPerFiveStar, 0.01)
        assertEquals(0, result.pity.currentPity)  // 最后一抽是五星
    }

    @Test
    fun `最欧最非 - 当前垫抽不参与`() {
        // 已完成：10, 20, 45 → 最欧10, 最非45
        // 当前：70抽未出金 → 不能算最非
        val records = buildRecords(count = 145, fiveStarPulls = listOf(10, 30, 75))
        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(listOf(10, 20, 45), result.fiveStar.intervals)
        assertEquals(10, result.fiveStar.minPulls)  // 最欧
        assertEquals(45, result.fiveStar.maxPulls)  // 最非（不是70！）
        assertEquals(70, result.pity.currentPity)
    }

    // ==================== 测试7：301/400 共享保底 - 总抽数独立 ====================

    @Test
    fun `301和400 - 总抽数和五星数独立统计`() {
        // 301: 50抽, 2五星
        val records301 = buildRecords(
            count = 50,
            fiveStarPulls = listOf(10, 30),
            poolType = GachaType.CHARACTER.value,
            startOrder = 1000000000000000000L
        )

        // 400: 30抽, 1五星
        val records400 = buildRecords(
            count = 30,
            fiveStarPulls = listOf(20),
            poolType = GachaType.CHARACTER_2.value,
            startOrder = 1000000000000000100L
        )

        // 计算 301 的统计（传入 400 作为共享池）
        val result301 = calculator.calculatePoolStats(
            records = records301,
            poolType = GachaType.CHARACTER.value,
            sharedPityRecords = records400
        )

        // 计算 400 的统计（传入 301 作为共享池）
        val result400 = calculator.calculatePoolStats(
            records = records400,
            poolType = GachaType.CHARACTER_2.value,
            sharedPityRecords = records301
        )

        // 基础统计独立
        assertEquals(50, result301.basic.totalPulls)
        assertEquals(2, result301.basic.fiveStarCount)
        assertEquals(30, result400.basic.totalPulls)
        assertEquals(1, result400.basic.fiveStarCount)

        // 不能变成 150 抽 3 五星
        assertNotEquals(80, result301.basic.totalPulls)
        assertNotEquals(3, result301.basic.fiveStarCount)
        assertNotEquals(80, result400.basic.totalPulls)
        assertNotEquals(3, result400.basic.fiveStarCount)
    }

    // ==================== 测试8：301/400 共享保底 - 垫抽合并 ====================

    @Test
    fun `301和400共享保底 - 当前垫抽合并计算`() {
        // 场景：301抽了50抽没出金，然后去400抽了30抽也没出金
        // 正确垫抽：80（共享保底累计）

        val records301 = buildRecords(
            count = 50,
            fiveStarPulls = emptyList(),
            poolType = GachaType.CHARACTER.value,
            startOrder = 1000000000000000000L
        )

        val records400 = buildRecords(
            count = 30,
            fiveStarPulls = emptyList(),
            poolType = GachaType.CHARACTER_2.value,
            startOrder = 1000000000000000100L
        )

        val result301 = calculator.calculatePoolStats(
            records = records301,
            poolType = GachaType.CHARACTER.value,
            sharedPityRecords = records400
        )

        val result400 = calculator.calculatePoolStats(
            records = records400,
            poolType = GachaType.CHARACTER_2.value,
            sharedPityRecords = records301
        )

        // 两个池的垫抽应该相同（都是 80）
        assertEquals(80, result301.pity.currentPity)
        assertEquals(80, result400.pity.currentPity)
    }

    @Test
    fun `301和400共享保底 - 400出金后垫抽重置`() {
        // 场景：301 抽了50抽没出金，然后 400 第30抽出了五星，之后又抽了15抽
        // 合并后：50 + 30 = 80抽时出金，然后15抽没出
        // 当前垫抽：15

        val records301 = buildRecords(
            count = 50,
            fiveStarPulls = emptyList(),
            poolType = GachaType.CHARACTER.value,
            startOrder = 1000000000000000000L
        )

        // 400: 第30抽出五星，然后继续15抽（共45抽）
        val records400 = buildRecords(
            count = 45,
            fiveStarPulls = listOf(30),
            poolType = GachaType.CHARACTER_2.value,
            startOrder = 1000000000000000100L
        )

        val result301 = calculator.calculatePoolStats(
            records = records301,
            poolType = GachaType.CHARACTER.value,
            sharedPityRecords = records400
        )

        val result400 = calculator.calculatePoolStats(
            records = records400,
            poolType = GachaType.CHARACTER_2.value,
            sharedPityRecords = records301
        )

        // 当前垫抽：15（400出金后又抽了15抽）
        assertEquals(15, result301.pity.currentPity)
        assertEquals(15, result400.pity.currentPity)
    }

    // ==================== 测试9：301/400 共享保底 - 五星间隔合并 ====================

    @Test
    fun `301和400共享保底 - 五星间隔合并计算`() {
        // 场景：
        // 301: 第50抽出金（顺序上的第一个五星）
        // 400: 之后第30抽出金（顺序上的第二个五星）
        // 合并后五星间隔应该是：50, 30
        // 而不是 301: 50, 400: 30 各自独立

        val records301 = buildRecords(
            count = 50,
            fiveStarPulls = listOf(50),
            poolType = GachaType.CHARACTER.value,
            startOrder = 1000000000000000000L
        )

        val records400 = buildRecords(
            count = 30,
            fiveStarPulls = listOf(30),
            poolType = GachaType.CHARACTER_2.value,
            startOrder = 1000000000000000100L
        )

        val result301 = calculator.calculatePoolStats(
            records = records301,
            poolType = GachaType.CHARACTER.value,
            sharedPityRecords = records400
        )

        val result400 = calculator.calculatePoolStats(
            records = records400,
            poolType = GachaType.CHARACTER_2.value,
            sharedPityRecords = records301
        )

        // 五星间隔应该相同（共享保底，合并计算）
        // 间隔：50（第一个五星在第50抽），30（第二个五星在第30抽，即50+30=80抽位置）
        assertEquals(listOf(50, 30), result301.fiveStar.intervals)
        assertEquals(listOf(50, 30), result400.fiveStar.intervals)

        // 平均出金也是相同的
        assertEquals(40.0, result301.fiveStar.avgPullsPerFiveStar, 0.01)
        assertEquals(40.0, result400.fiveStar.avgPullsPerFiveStar, 0.01)

        // 最欧/最非也相同
        assertEquals(30, result301.fiveStar.minPulls)
        assertEquals(50, result301.fiveStar.maxPulls)
    }

    // ==================== 测试10：各池独立性 ====================

    @Test
    fun `武器池独立 - 不与角色池共享`() {
        val charRecords = buildRecords(
            count = 50,
            fiveStarPulls = listOf(10),
            poolType = GachaType.CHARACTER.value,
            startOrder = 1000000000000000000L
        )

        val weaponRecords = buildRecords(
            count = 30,
            fiveStarPulls = listOf(20),
            poolType = GachaType.WEAPON.value,
            startOrder = 1000000000000000100L
        )

        val charResult = calculator.calculatePoolStats(charRecords, GachaType.CHARACTER.value)
        val weaponResult = calculator.calculatePoolStats(weaponRecords, GachaType.WEAPON.value)

        // 各自独立统计
        assertEquals(50, charResult.basic.totalPulls)
        assertEquals(1, charResult.basic.fiveStarCount)
        assertEquals(listOf(10), charResult.fiveStar.intervals)
        assertEquals(40, charResult.pity.currentPity)

        assertEquals(30, weaponResult.basic.totalPulls)
        assertEquals(1, weaponResult.basic.fiveStarCount)
        assertEquals(listOf(20), weaponResult.fiveStar.intervals)
        assertEquals(10, weaponResult.pity.currentPity)

        // 保底上限不同
        assertEquals(90, charResult.pity.pityCeiling)
        assertEquals(80, weaponResult.pity.pityCeiling)
    }

    @Test
    fun `常驻池独立 - 不与其他池共享`() {
        val standardRecords = buildRecords(
            count = 100,
            fiveStarPulls = listOf(85),
            poolType = GachaType.STANDARD.value
        )

        val result = calculator.calculatePoolStats(standardRecords, GachaType.STANDARD.value)

        assertEquals(100, result.basic.totalPulls)
        assertEquals(1, result.basic.fiveStarCount)
        assertEquals(listOf(85), result.fiveStar.intervals)
        assertEquals(15, result.pity.currentPity)
        assertEquals(90, result.pity.pityCeiling)
    }

    // ==================== 测试11：50/50 状态 ====================

    @Test
    fun `50_50状态 - 上一次是UP则下次不是大保底`() {
        // 最近一次五星是 UP → 下次 50/50（isGuaranteed = false）
        val records = buildRecords(
            count = 50,
            fiveStarPulls = listOf(40),
            poolType = GachaType.CHARACTER.value,
            nameByPull = mapOf(40 to "限定角色A")  // 非常驻 = UP
        )

        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        // 最近一次是 UP（非常驻）→ 下次不是大保底
        assertFalse(result.pity.isGuaranteed)
        assertEquals("限定角色A", result.pity.lastFiveStarName)
    }

    @Test
    fun `50_50状态 - 上一次歪了常驻则下次大保底`() {
        // 最近一次五星是常驻（歪了）→ 下次大保底（isGuaranteed = true）
        val records = buildRecords(
            count = 50,
            fiveStarPulls = listOf(40),
            poolType = GachaType.CHARACTER.value,
            nameByPull = mapOf(40 to "迪卢克")  // 常驻 = 歪了
        )

        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        // 最近一次是常驻 → 下次大保底
        assertTrue(result.pity.isGuaranteed)
        assertEquals("迪卢克", result.pity.lastFiveStarName)
    }

    @Test
    fun `50_50状态 - 无五星则不是大保底`() {
        val records = buildRecords(
            count = 50,
            fiveStarPulls = emptyList(),
            poolType = GachaType.CHARACTER.value
        )

        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertFalse(result.pity.isGuaranteed)
        assertNull(result.pity.lastFiveStarName)
    }

    // ==================== 测试12：UP 率 ====================

    @Test
    fun `UP率 - 使用常驻列表兜底判断`() {
        // 3个五星：2个UP（限定）+ 1个歪（常驻）
        val records = buildRecords(
            count = 200,
            fiveStarPulls = listOf(50, 100, 150),
            poolType = GachaType.CHARACTER.value,
            nameByPull = mapOf(
                50 to "限定角色A",   // UP
                100 to "迪卢克",     // 歪
                150 to "限定角色B"   // UP
            )
        )

        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(2, result.up.upFiveStarCount)
        assertEquals(1, result.up.lostFiveStarCount)
        assertEquals(2 / 3.0, result.up.upRate, 0.01)
    }

    @Test
    fun `UP率 - upItems参数精确匹配`() {
        val records = buildRecords(
            count = 150,
            fiveStarPulls = listOf(50, 100),
            poolType = GachaType.CHARACTER.value,
            nameByPull = mapOf(
                50 to "胡桃",     // 在 upItems 中 → UP
                100 to "刻晴"     // 不在 upItems 中 → 歪
            )
        )

        val result = calculator.calculatePoolStats(
            records = records,
            poolType = GachaType.CHARACTER.value,
            upItems = listOf("胡桃")
        )

        assertEquals(1, result.up.upFiveStarCount)
        assertEquals(1, result.up.lostFiveStarCount)
        assertEquals(0.5, result.up.upRate, 0.01)
    }

    // ==================== 测试13：generateReport 全局统计 ====================

    @Test
    fun `generateReport - 全局平均出金基于所有已完成间隔`() {
        val charRecords = buildRecords(
            count = 100,
            fiveStarPulls = listOf(10, 80),  // 间隔: 10, 70
            poolType = GachaType.CHARACTER.value,
            startOrder = 1000000000000000000L
        )

        val weaponRecords = buildRecords(
            count = 100,
            fiveStarPulls = listOf(20, 85),  // 间隔: 20, 65
            poolType = GachaType.WEAPON.value,
            startOrder = 2000000000000000000L
        )

        val standardRecords = buildRecords(
            count = 50,
            fiveStarPulls = listOf(45),  // 间隔: 45
            poolType = GachaType.STANDARD.value,
            startOrder = 3000000000000000000L
        )

        val report = calculator.generateReport(
            characterRecords = charRecords,
            weaponRecords = weaponRecords,
            standardRecords = standardRecords
        )

        // 总抽数
        assertEquals(250, report.totalPulls)
        assertEquals(5, report.totalFiveStars)

        // 所有已完成间隔：10, 70, 20, 65, 45
        val allIntervals = listOf(10, 70, 20, 65, 45)
        val expectedAvg = allIntervals.average()
        assertEquals(expectedAvg, report.avgPullsPerFiveStar, 0.01)

        // 最欧/最非
        assertEquals(10, report.bestLuck)
        assertEquals(70, report.worstLuck)
    }

    // ==================== 测试14：orderNumber 排序正确性 ====================

    @Test
    fun `orderNumber排序 - 乱序输入也能正确计算`() {
        // 故意打乱顺序输入
        val records = listOf(
            makeRecord(3, 3),  // 第3抽 3星
            makeRecord(1, 3),  // 第1抽 3星
            makeRecord(5, 5),  // 第5抽 5星
            makeRecord(2, 3),  // 第2抽 3星
            makeRecord(4, 3),  // 第4抽 3星
        ).shuffled()  // 再打乱一次

        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        assertEquals(5, result.basic.totalPulls)
        assertEquals(1, result.basic.fiveStarCount)
        assertEquals(listOf(5), result.fiveStar.intervals)
        assertEquals(0, result.pity.currentPity)
    }

    // ==================== 测试15：相同时间不同 orderNumber ====================

    @Test
    fun `同一秒多抽 - 按orderNumber排序而非time`() {
        // 模拟同一秒内出了两个五星（胡桃和迪卢克）
        // orderNumber 501 先出（胡桃），orderNumber 503 后出（迪卢克）
        // 如果按 time 排序，顺序可能颠倒，导致间隔计算错误

        val records = buildRecords(
            count = 510,
            fiveStarPulls = listOf(100, 501, 503),  // 第100、501、503抽出金
            poolType = GachaType.CHARACTER.value,
            nameByPull = mapOf(
                501 to "胡桃",
                503 to "迪卢克"
            )
        )

        val result = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        // 间隔应该是：100, 401, 2
        // 第100抽第1金 → 间隔100
        // 第501抽第2金 → 501-100 = 401
        // 第503抽第3金 → 503-501 = 2
        assertEquals(listOf(100, 401, 2), result.fiveStar.intervals)

        // 最欧 = 2（双黄），最非 = 401
        assertEquals(2, result.fiveStar.minPulls)
        assertEquals(401, result.fiveStar.maxPulls)

        // 垫抽：510 - 503 = 7
        assertEquals(7, result.pity.currentPity)
    }
}
