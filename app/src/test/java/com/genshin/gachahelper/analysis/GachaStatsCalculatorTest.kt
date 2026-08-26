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
            id = orderNumber, // 用 orderNumber 作为唯一 id，保证映射测试可用
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

    // ==================== 测试A：calculateFiveStarIntervals 必须排序 ====================

    /**
     * 测试 calculateFiveStarIntervals 在传入 DESC 序记录时仍能正确计算间隔。
     *
     * 场景：迪希雅（第50抽）→ 哥伦比娅（第59抽），间隔应为 9。
     * DAO 返回 DESC 序，如果不排序，间隔和映射会错位。
     */
    @Test
    fun `calculateFiveStarIntervals 传入DESC序记录仍正确计算间隔`() {
        val records = buildRecords(
            count = 60,
            fiveStarPulls = listOf(50, 59),
            nameByPull = mapOf(50 to "迪希雅", 59 to "哥伦比娅")
        )
        // 模拟 DAO 返回的 DESC 序
        val descRecords = records.sortedByDescending { it.orderNumber }

        val intervals = calculator.calculateFiveStarIntervals(descRecords)
        // 第一个五星（迪希雅）间隔 = 50，第二个五星（哥伦比娅）间隔 = 59-50 = 9
        assertEquals(listOf(50, 9), intervals)
    }

    // ==================== 测试B：五星ID与interval映射不依赖列表顺序 ====================

    /**
     * 测试五星 ID → interval 映射不依赖列表顺序。
     *
     * 构造三个五星 A(第10抽) B(第20抽) C(第30抽)。
     * 即使 UI 把显示顺序打乱为 C B A，
     * 每个五星的 interval 仍然正确映射到自己的 id。
     */
    @Test
    fun `五星ID与interval映射不依赖UI显示顺序`() {
        val records = buildRecords(
            count = 30,
            fiveStarPulls = listOf(10, 20, 30),
            nameByPull = mapOf(10 to "A", 20 to "B", 30 to "C")
        )

        val intervals = calculator.calculateFiveStarIntervals(records)
        val fiveStarsInOrder = records
            .filter { it.rarity == 5 }
            .sortedBy { it.orderNumber }

        // 建立映射：id → interval
        val intervalById = mutableMapOf<Long, Int>()
        for (i in fiveStarsInOrder.indices) {
            if (i < intervals.size) {
                intervalById[fiveStarsInOrder[i].id] = intervals[i]
            }
        }

        // 间隔：A=10, B=20-10=10, C=30-20=10
        assertEquals(listOf(10, 10, 10), intervals)

        // 即使 UI 用乱序排列 C B A，每个五星仍然映射到自己的 interval
        val uiDisplayOrder = fiveStarsInOrder.reversed() // C, B, A
        assertEquals(10, intervalById[uiDisplayOrder[0].id]) // C → 10
        assertEquals(10, intervalById[uiDisplayOrder[1].id]) // B → 10
        assertEquals(10, intervalById[uiDisplayOrder[2].id]) // A → 10

        // 改变间隔使之更明显
        val records2 = buildRecords(
            count = 80,
            fiveStarPulls = listOf(10, 50, 80),
            nameByPull = mapOf(10 to "A", 50 to "B", 80 to "C")
        )
        val intervals2 = calculator.calculateFiveStarIntervals(records2)
        val fiveStars2 = records2.filter { it.rarity == 5 }.sortedBy { it.orderNumber }
        val map2 = mutableMapOf<Long, Int>()
        for (i in fiveStars2.indices) {
            if (i < intervals2.size) map2[fiveStars2[i].id] = intervals2[i]
        }
        // A=10, B=50-10=40, C=80-50=30
        assertEquals(listOf(10, 40, 30), intervals2)
        // 乱序 C A B → 30, 10, 40
        val shuffled = listOf(fiveStars2[2], fiveStars2[0], fiveStars2[1])
        assertEquals(30, map2[shuffled[0].id])
        assertEquals(10, map2[shuffled[1].id])
        assertEquals(40, map2[shuffled[2].id])
    }

    // ==================== 测试C：首页平均出金不能用总抽数÷五星数量 ====================

    /**
     * 测试 generateReport 的平均出金 = 已完成间隔的平均值，
     * 而不是 totalPulls / totalFiveStars。
     *
     * 场景：角色池 50抽2金（间隔 10, 40），武器池 80抽1金（间隔 80）
     * 已完成间隔 = [10, 40, 80]，平均 = 130/3 ≈ 43.33
     * 错误算法 = (50+80) / (2+1) = 130/3 ≈ 43.33 （巧合相同）
     *
     * 更好的场景：角色池 100抽2金（间隔10, 10），之后80抽无金
     * 已完成间隔 = [10, 10]，平均 = 10
     * 错误算法 = 100 / 2 = 50
     */
    @Test
    fun `generateReport 平均出金等于已完成间隔均值而非总抽数除五星数`() {
        val charRecords = buildRecords(count = 100, fiveStarPulls = listOf(10, 20))
        val weaponRecords = buildRecords(
            count = 80, fiveStarPulls = listOf(80),
            poolType = GachaType.WEAPON.value, startOrder = 2000000000000000000L
        )
        val standardRecords = emptyList<GachaRecordEntity>()

        val report = calculator.generateReport(
            characterRecords = charRecords,
            character2Records = emptyList(),
            weaponRecords = weaponRecords,
            standardRecords = standardRecords
        )

        // 已完成间隔：角色池 [10, 10]，武器池 [80]
        // 平均 = (10 + 10 + 80) / 3 = 100 / 3 ≈ 33.33
        assertEquals(33.33, report.avgPullsPerFiveStar, 0.1)

        // 错误算法：180 / 3 = 60，验证确实不同
        assertNotEquals(60.0, report.avgPullsPerFiveStar, 0.1)
    }

    // ==================== 测试D：301/400 不重复统计全局间隔 ====================

    /**
     * 测试 generateReport 中 301/400 共享间隔不被重复计入全局统计。
     *
     * 场景：301 有 2 个五星间隔 [10, 50]，400 有 2 个五星间隔 [30, 60]
     * 因为 301/400 共享保底，四者合并后的间隔应为 [10, 30+X, 50+Y, 60+Z] 等
     *
     * 简化场景：
     * 301：第10抽五星A
     * 400：第40抽五星B（合并后距A = 30）
     * 301：第70抽五星C（合并后距B = 30）
     *
     * 正确全局间隔 = [10, 30, 30]，共3个，平均 = 70/3 ≈ 23.33
     * 错误（重复统计）= 6个间隔（301的3个 + 400的3个），平均会不同
     */
    @Test
    fun `generateReport 中301和400共享间隔不重复计入全局`() {
        // 301 记录：第10抽五星A，第69抽五星C（最后一抽）
        val records301 = buildRecords(
            count = 69,
            fiveStarPulls = listOf(10, 69),
            poolType = GachaType.CHARACTER.value,
            startOrder = 1000000000000000000L,
            nameByPull = mapOf(10 to "A", 69 to "C")
        )
        // 400 记录：只有1抽五星B，orderNumber 落在 301 的 A 和 C 之间
        val records400 = buildRecords(
            count = 1,
            fiveStarPulls = listOf(1),
            poolType = GachaType.CHARACTER_2.value,
            startOrder = 1000000000000000039L, // orderNumber = ...040
            nameByPull = mapOf(1 to "B")
        )
        // 武器池：1个五星间隔 = 30
        val weaponRecords = buildRecords(
            count = 30,
            fiveStarPulls = listOf(30),
            poolType = GachaType.WEAPON.value,
            startOrder = 2000000000000000000L
        )

        val report = calculator.generateReport(
            characterRecords = records301,
            character2Records = records400,
            weaponRecords = weaponRecords,
            standardRecords = emptyList()
        )

        // 合并后按 orderNumber 排序：
        // 001-009(301), 010(A), 011-039(301), 040(B), 041-069(C)
        // 位置：A=10, B=40, C=70
        // 间隔：A=10, B=40-10=30, C=70-40=30
        // 角色池共享间隔（一份）：[10, 30, 30]
        // 武器池间隔：[30]
        // 全局间隔 = [10, 30, 30, 30]，共4个

        // 正确平均 = (10 + 30 + 30 + 30) / 4 = 100 / 4 = 25.0
        assertEquals(25.0, report.avgPullsPerFiveStar, 0.1)

        // 五星总数仍然独立计数：301有2个 + 400有1个 + 武器1个 = 4
        assertEquals(4, report.totalFiveStars)

        // 总抽数独立计数：69 + 1 + 30 = 100
        assertEquals(100, report.totalPulls)
    }

    // ==================== 测试N：概率模型 & 运气分 ====================

    /**
     * 测试概率模型基本性质：
     * - 第 1 抽出金概率 ≈ 基础概率
     * - 硬保底抽数的生存函数 = 0
     * - CDF 在硬保底处 = 1
     */
    @Test
    fun `概率模型基本性质验证`() {
        // 角色池：第 1 抽概率 ≈ 0.6%
        val p1 = GachaProbabilityModel.pmf(1, GachaType.CHARACTER.value)
        assertEquals(0.006, p1, 0.0001)

        // 角色池：第 90 抽（硬保底）生存函数 = 0
        val s90 = GachaProbabilityModel.survival(90, GachaType.CHARACTER.value)
        assertEquals(0.0, s90, 0.0001)

        // 角色池：CDF 在 90 抽 = 1
        val cdf90 = GachaProbabilityModel.cdf(90, GachaType.CHARACTER.value)
        assertEquals(1.0, cdf90, 0.0001)

        // 武器池：第 80 抽（硬保底）生存函数 = 0
        val s80 = GachaProbabilityModel.survival(80, GachaType.WEAPON.value)
        assertEquals(0.0, s80, 0.0001)
    }

    /**
     * 测试运气分边界值：
     * - 1 抽 = 极高分（接近 100）
     * - 90 抽 = 0 分
     * - 期望值附近 ≈ 50 分
     */
    @Test
    fun `运气分边界值验证`() {
        // 1 抽出金：极欧，接近 100 分
        val score1 = GachaProbabilityModel.calculateSingleLuckScore(1, GachaType.CHARACTER.value)
        assertTrue("1 抽出金应该接近 100 分，实际 $score1", score1 >= 99)

        // 90 抽出金：极非，0 分
        val score90 = GachaProbabilityModel.calculateSingleLuckScore(90, GachaType.CHARACTER.value)
        assertEquals(0, score90)

        // 0 抽：视为 100 分（边界）
        val score0 = GachaProbabilityModel.calculateSingleLuckScore(0, GachaType.CHARACTER.value)
        assertEquals(100, score0)
    }

    /**
     * 测试 FiveStarStats 中的运气分是否正确计算。
     * 使用简单数据：5 个五星，间隔分别为 30、60、45、75、20
     */
    @Test
    fun `FiveStarStats 运气分计算`() {
        val records = buildList {
            var orderNum = 1L
            // 第一个五星：第 30 抽
            for (i in 1..29) add(makeRecord(orderNum++, 3))
            add(makeRecord(orderNum++, 5, itemName = "五星1"))
            // 第二个五星：第 60 抽
            for (i in 1..59) add(makeRecord(orderNum++, 3))
            add(makeRecord(orderNum++, 5, itemName = "五星2"))
            // 第三个五星：第 45 抽
            for (i in 1..44) add(makeRecord(orderNum++, 3))
            add(makeRecord(orderNum++, 5, itemName = "五星3"))
            // 第四个五星：第 75 抽
            for (i in 1..74) add(makeRecord(orderNum++, 3))
            add(makeRecord(orderNum++, 5, itemName = "五星4"))
            // 第五个五星：第 20 抽
            for (i in 1..19) add(makeRecord(orderNum++, 3))
            add(makeRecord(orderNum++, 5, itemName = "五星5"))
        }

        val stats = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        // 间隔应该是：30、60、45、75、20
        assertEquals(listOf(30, 60, 45, 75, 20), stats.fiveStar.intervals)

        // 平均出金 = (30+60+45+75+20) / 5 = 230 / 5 = 46
        assertEquals(46.0, stats.fiveStar.avgPullsPerFiveStar, 0.1)

        // 运气分应该有值（0~100）
        assertTrue("运气分应在 0~100 之间，实际 ${stats.fiveStar.luckScore}",
            stats.fiveStar.luckScore in 0..100)

        // 5 个样本 → 可信度 = LOW（参考）
        assertEquals(LuckConfidence.LOW, stats.fiveStar.luckConfidence)

        // 单次运气分数量应该等于间隔数量
        assertEquals(5, stats.fiveStar.singleLuckScores.size)
    }

    /**
     * 测试 301+400 共享保底池的运气分是否正确。
     * 两个池共享五星间隔，运气分也应该基于共享间隔计算。
     */
    @Test
    fun `301+400共享保底池运气分一致`() {
        // 301 池：前 20 抽普通，第 21 抽出五星
        val records301 = buildList {
            for (i in 1..20) add(makeRecord(i.toLong(), 3, GachaType.CHARACTER.value))
            add(makeRecord(21L, 5, GachaType.CHARACTER.value, "角色A"))
            // 再 40 抽普通
            for (i in 22..61) add(makeRecord(i.toLong(), 3, GachaType.CHARACTER.value))
        }

        // 400 池：第 62 抽出五星（接着 301 的第 61 抽）
        val records400 = buildList {
            add(makeRecord(62L, 5, GachaType.CHARACTER_2.value, "角色B"))
            // 再 30 抽普通
            for (i in 63..92) add(makeRecord(i.toLong(), 3, GachaType.CHARACTER_2.value))
        }

        val stats301 = calculator.calculatePoolStats(
            records301, GachaType.CHARACTER.value,
            sharedPityRecords = records400
        )
        val stats400 = calculator.calculatePoolStats(
            records400, GachaType.CHARACTER_2.value,
            sharedPityRecords = records301
        )

        // 两个池的五星间隔应该相同（共享保底）
        assertEquals(stats301.fiveStar.intervals, stats400.fiveStar.intervals)

        // 运气分也应该相同
        assertEquals(stats301.fiveStar.luckScore, stats400.fiveStar.luckScore)

        // 间隔：第一个五星在第 21 抽（301 池），第二个五星在第 62 抽（400 池）
        // 按合并顺序：1~21（301，第21抽五星），22~62（301的22~61 + 400的62，共41抽，第62抽五星）
        // 间隔 = [21, 41]
        assertEquals(listOf(21, 41), stats301.fiveStar.intervals)
    }

    /**
     * 测试 generateReport 综合运气分。
     * 角色池共享间隔只计一份，不能重复计入。
     */
    @Test
    fun `generateReport综合运气分_角色池不重复计入`() {
        // 301 池：30 抽出五星
        val records301 = buildList {
            for (i in 1..29) add(makeRecord(i.toLong(), 3, GachaType.CHARACTER.value))
            add(makeRecord(30L, 5, GachaType.CHARACTER.value, "角色A"))
        }

        // 400 池：接着 60 抽出五星（间隔 60）
        val records400 = buildList {
            for (i in 31..90) add(makeRecord(i.toLong(), 3, GachaType.CHARACTER_2.value))
            add(makeRecord(91L, 5, GachaType.CHARACTER_2.value, "角色B"))
        }

        // 武器池：40 抽出五星
        val weaponRecords = buildList {
            for (i in 100..139) add(makeRecord(i.toLong(), 3, GachaType.WEAPON.value))
            add(makeRecord(140L, 5, GachaType.WEAPON.value, "武器A"))
        }

        val report = calculator.generateReport(
            characterRecords = records301,
            character2Records = records400,
            weaponRecords = weaponRecords,
            standardRecords = emptyList()
        )

        // 角色池共享间隔：[30, 61]（第 30 抽和第 91 抽，间隔 30 和 61）
        // 武器池间隔：[41]
        // 全局间隔数 = 3 个

        // 综合运气分应该有值
        assertTrue("综合运气分应在 0~100 之间，实际 ${report.overallLuckScore}",
            report.overallLuckScore in 0..100)

        // 可信度：3 个样本 → INSUFFICIENT（数据较少）
        assertEquals(LuckConfidence.INSUFFICIENT, report.overallLuckConfidence)

        // 全局平均出金：角色池共享 [30, 61] + 武器 [41] = (30+61+41)/3 = 44
        assertEquals(44.0, report.avgPullsPerFiveStar, 0.5)
    }

    /**
     * 测试可信度等级划分。
     */
    @Test
    fun `可信度等级划分`() {
        assertEquals(LuckConfidence.INSUFFICIENT, LuckConfidence.fromSampleCount(0))
        assertEquals(LuckConfidence.INSUFFICIENT, LuckConfidence.fromSampleCount(1))
        assertEquals(LuckConfidence.INSUFFICIENT, LuckConfidence.fromSampleCount(3))
        assertEquals(LuckConfidence.LOW, LuckConfidence.fromSampleCount(4))
        assertEquals(LuckConfidence.LOW, LuckConfidence.fromSampleCount(9))
        assertEquals(LuckConfidence.MEDIUM, LuckConfidence.fromSampleCount(10))
        assertEquals(LuckConfidence.MEDIUM, LuckConfidence.fromSampleCount(19))
        assertEquals(LuckConfidence.HIGH, LuckConfidence.fromSampleCount(20))
        assertEquals(LuckConfidence.HIGH, LuckConfidence.fromSampleCount(50))
    }

    /**
     * 回归测试：五星间隔不能超过硬保底（90 抽角色池）。
     * 数据不完整时第一条间隔可能 > 90，这是物理上不可能的值。
     * 注意：calculateFiveStarStats 不过滤（保留原始数据供排查），
     * 但 UI 层和 HistoryViewModel 会用 pityCeiling 过滤显示。
     */
    @Test
    fun `回归测试_五星间隔超过保底上限的异常值`() {
        // 模拟数据不完整：只有一个五星，在第 120 条记录的位置
        // （真实情况是数据缺失了前面的五星记录）
        val records = buildList {
            for (i in 1..119) add(makeRecord(i.toLong(), 3))
            add(makeRecord(120L, 5, itemName = "异常五星"))
        }

        val stats = calculator.calculatePoolStats(records, GachaType.CHARACTER.value)

        // 原始间隔 = 120（超过保底上限，是数据不完整的标志）
        assertEquals(120, stats.fiveStar.intervals.firstOrNull())

        // 公共方法 calculateFiveStarIntervals 不过滤（保持 v1.5.2 行为）
        // 过滤逻辑在调用方（HistoryViewModel / HomeViewModel）处理
    }

    /**
     * 回归测试：空数据时运气分和可信度的默认值。
     */
    @Test
    fun `回归测试_空数据运气分默认值`() {
        val stats = calculator.calculatePoolStats(emptyList(), GachaType.CHARACTER.value)

        assertEquals(0, stats.fiveStar.luckScore)
        assertEquals(emptyList<Int>(), stats.fiveStar.singleLuckScores)
        assertEquals(LuckConfidence.INSUFFICIENT, stats.fiveStar.luckConfidence)
    }
}
