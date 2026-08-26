package com.genshin.gachahelper.analysis

import com.genshin.gachahelper.data.model.GachaType
import org.junit.Assert.*
import org.junit.Test

/**
 * 五星概率模型 & 运气分 单元测试
 *
 * 覆盖：
 * - 概率模型基础正确性（PMF/CDF/期望值）
 * - 单次运气分边界与单调性
 * - 极端数据（1抽、90抽、0抽、负数）
 * - 统计可信度等级
 * - LuckVerdict 评级边界
 */
class GachaProbabilityModelTest {

    // ==================== 概率模型基础测试 ====================

    @Test
    fun `角色池期望出金应在62-63抽之间`() {
        val config = GachaProbabilityModel.getPityConfig(GachaType.CHARACTER.value)
        var ev = 0.0
        for (n in 1..config.hardPity) {
            ev += n * GachaProbabilityModel.pmf(n, config)
        }
        assertTrue("期望应 ≈ 62.3，实际 $ev", ev in 62.0..63.0)
    }

    @Test
    fun `武器池期望出金应在53-54抽之间`() {
        val config = GachaProbabilityModel.getPityConfig(GachaType.WEAPON.value)
        var ev = 0.0
        for (n in 1..config.hardPity) {
            ev += n * GachaProbabilityModel.pmf(n, config)
        }
        assertTrue("期望应 ≈ 53.3，实际 $ev", ev in 53.0..54.0)
    }

    @Test
    fun `PMF总和为1 - 角色池`() {
        val config = GachaProbabilityModel.getPityConfig(GachaType.CHARACTER.value)
        var sum = 0.0
        for (n in 1..config.hardPity) {
            sum += GachaProbabilityModel.pmf(n, config)
        }
        assertEquals("PMF 总和应为 1", 1.0, sum, 0.001)
    }

    @Test
    fun `PMF总和为1 - 武器池`() {
        val config = GachaProbabilityModel.getPityConfig(GachaType.WEAPON.value)
        var sum = 0.0
        for (n in 1..config.hardPity) {
            sum += GachaProbabilityModel.pmf(n, config)
        }
        assertEquals("PMF 总和应为 1", 1.0, sum, 0.001)
    }

    @Test
    fun `CDF在保底处为1`() {
        val charConfig = GachaProbabilityModel.getPityConfig(GachaType.CHARACTER.value)
        val wepConfig = GachaProbabilityModel.getPityConfig(GachaType.WEAPON.value)
        assertEquals("角色池CDF(90)=1", 1.0, GachaProbabilityModel.cdf(90, charConfig), 0.001)
        assertEquals("武器池CDF(80)=1", 1.0, GachaProbabilityModel.cdf(80, wepConfig), 0.001)
    }

    @Test
    fun `CDF在0抽处为0`() {
        val config = GachaProbabilityModel.getPityConfig(GachaType.CHARACTER.value)
        assertEquals(0.0, GachaProbabilityModel.cdf(0, config), 0.001)
    }

    // ==================== 单次运气分：正常值点 ====================

    @Test
    fun `单次运气分 - 角色池各抽数验证`() {
        val poolType = GachaType.CHARACTER.value
        // 抽数越少 → 运气分不能更低
        assertTrue("1抽 > 10抽",
            GachaProbabilityModel.calculateSingleLuckScore(1, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(10, poolType))
        assertTrue("10抽 > 30抽",
            GachaProbabilityModel.calculateSingleLuckScore(10, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(30, poolType))
        assertTrue("30抽 > 50抽",
            GachaProbabilityModel.calculateSingleLuckScore(30, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(50, poolType))
        assertTrue("50抽 > 70抽",
            GachaProbabilityModel.calculateSingleLuckScore(50, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(70, poolType))
        assertTrue("70抽 > 80抽",
            GachaProbabilityModel.calculateSingleLuckScore(70, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(80, poolType))
        assertTrue("80抽 > 90抽",
            GachaProbabilityModel.calculateSingleLuckScore(80, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(90, poolType))
    }

    @Test
    fun `单次运气分 - 武器池各抽数验证`() {
        val poolType = GachaType.WEAPON.value
        assertTrue("1抽 > 10抽",
            GachaProbabilityModel.calculateSingleLuckScore(1, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(10, poolType))
        assertTrue("30抽 > 50抽",
            GachaProbabilityModel.calculateSingleLuckScore(30, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(50, poolType))
        assertTrue("60抽 > 70抽",
            GachaProbabilityModel.calculateSingleLuckScore(60, poolType) >
            GachaProbabilityModel.calculateSingleLuckScore(70, poolType))
    }

    // ==================== 单次运气分：边界与极端值 ====================

    @Test
    fun `运气分范围始终在0到100之间 - 角色池`() {
        val poolType = GachaType.CHARACTER.value
        for (n in 0..95) {
            val score = GachaProbabilityModel.calculateSingleLuckScore(n, poolType)
            assertTrue("n=$n 时分数 $score 应 >= 0", score >= 0)
            assertTrue("n=$n 时分数 $score 应 <= 100", score <= 100)
        }
    }

    @Test
    fun `1抽五星运气分接近100 - 极欧`() {
        val score = GachaProbabilityModel.calculateSingleLuckScore(1, GachaType.CHARACTER.value)
        assertTrue("1抽应接近100分，实际 $score", score >= 95)
    }

    @Test
    fun `90抽五星运气分为0 - 极非`() {
        val score = GachaProbabilityModel.calculateSingleLuckScore(90, GachaType.CHARACTER.value)
        assertEquals("90抽应为0分", 0, score)
    }

    @Test
    fun `0抽或负数返回100（边界保护）`() {
        assertEquals(100, GachaProbabilityModel.calculateSingleLuckScore(0, GachaType.CHARACTER.value))
        assertEquals(100, GachaProbabilityModel.calculateSingleLuckScore(-5, GachaType.CHARACTER.value))
    }

    @Test
    fun `超过保底上限返回0（边界保护）`() {
        assertEquals(0, GachaProbabilityModel.calculateSingleLuckScore(91, GachaType.CHARACTER.value))
        assertEquals(0, GachaProbabilityModel.calculateSingleLuckScore(100, GachaType.CHARACTER.value))
        assertEquals(0, GachaProbabilityModel.calculateSingleLuckScore(81, GachaType.WEAPON.value))
    }

    @Test
    fun `运气分单调递减 - 角色池`() {
        val poolType = GachaType.CHARACTER.value
        var prevScore = 101
        for (n in 1..90) {
            val score = GachaProbabilityModel.calculateSingleLuckScore(n, poolType)
            assertTrue("n=$n 时分数 $score 不应高于前值 $prevScore", score <= prevScore)
            prevScore = score
        }
    }

    @Test
    fun `运气分单调递减 - 武器池`() {
        val poolType = GachaType.WEAPON.value
        var prevScore = 101
        for (n in 1..80) {
            val score = GachaProbabilityModel.calculateSingleLuckScore(n, poolType)
            assertTrue("n=$n 时分数 $score 不应高于前值 $prevScore", score <= prevScore)
            prevScore = score
        }
    }

    // ==================== 统计可信度 ====================

    @Test
    fun `统计可信度等级判定`() {
        assertEquals(LuckConfidence.INSUFFICIENT, LuckConfidence.fromSampleCount(0))
        assertEquals(LuckConfidence.INSUFFICIENT, LuckConfidence.fromSampleCount(1))
        assertEquals(LuckConfidence.INSUFFICIENT, LuckConfidence.fromSampleCount(3))
        assertEquals(LuckConfidence.LOW, LuckConfidence.fromSampleCount(4))
        assertEquals(LuckConfidence.LOW, LuckConfidence.fromSampleCount(9))
        assertEquals(LuckConfidence.MEDIUM, LuckConfidence.fromSampleCount(10))
        assertEquals(LuckConfidence.MEDIUM, LuckConfidence.fromSampleCount(19))
        assertEquals(LuckConfidence.HIGH, LuckConfidence.fromSampleCount(20))
        assertEquals(LuckConfidence.HIGH, LuckConfidence.fromSampleCount(100))
    }

    // ==================== 运气评级 ====================

    @Test
    fun `运气评级边界`() {
        assertEquals("极欧", LuckVerdict.fromScore(90))
        assertEquals("极欧", LuckVerdict.fromScore(100))
        assertEquals("非常欧", LuckVerdict.fromScore(80))
        assertEquals("非常欧", LuckVerdict.fromScore(89))
        assertEquals("比较欧", LuckVerdict.fromScore(70))
        assertEquals("比较欧", LuckVerdict.fromScore(79))
        assertEquals("略欧", LuckVerdict.fromScore(60))
        assertEquals("略欧", LuckVerdict.fromScore(69))
        assertEquals("正常", LuckVerdict.fromScore(40))
        assertEquals("正常", LuckVerdict.fromScore(59))
        assertEquals("略非", LuckVerdict.fromScore(30))
        assertEquals("略非", LuckVerdict.fromScore(39))
        assertEquals("比较非", LuckVerdict.fromScore(20))
        assertEquals("比较非", LuckVerdict.fromScore(29))
        assertEquals("非常非", LuckVerdict.fromScore(10))
        assertEquals("非常非", LuckVerdict.fromScore(19))
        assertEquals("极非", LuckVerdict.fromScore(0))
        assertEquals("极非", LuckVerdict.fromScore(9))
    }

    // ==================== 不同卡池概率配置 ====================

    @Test
    fun `角色池和常驻池共享同一套概率配置`() {
        val charConfig = GachaProbabilityModel.getPityConfig(GachaType.CHARACTER.value)
        val stdConfig = GachaProbabilityModel.getPityConfig(GachaType.STANDARD.value)
        assertEquals(charConfig.baseRate, stdConfig.baseRate, 0.0)
        assertEquals(charConfig.softPityStart, stdConfig.softPityStart)
        assertEquals(charConfig.hardPity, stdConfig.hardPity)
    }

    @Test
    fun `武器池概率配置与角色池不同`() {
        val charConfig = GachaProbabilityModel.getPityConfig(GachaType.CHARACTER.value)
        val wepConfig = GachaProbabilityModel.getPityConfig(GachaType.WEAPON.value)
        assertNotEquals(charConfig.hardPity, wepConfig.hardPity)
        assertNotEquals(charConfig.baseRate, wepConfig.baseRate)
        assertNotEquals(charConfig.softPityStart, wepConfig.softPityStart)
    }
}
