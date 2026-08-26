package com.genshin.gachahelper.analysis

import com.genshin.gachahelper.data.model.GachaType
import kotlin.math.exp
import kotlin.math.ln

/**
 * 原神五星抽卡概率模型
 *
 * 基于游戏实际抽卡规则建立：
 * - 基础概率
 * - 软保底（概率递增）
 * - 硬保底（必出）
 *
 * 模型来源：原神官方公布 + 玩家社区大样本统计验证
 * - 角色池/常驻池/集录池：基础 0.6%，第74抽起软保底(+6%/抽)，第90抽硬保底
 * - 武器池：基础 0.7%，第63抽起软保底(+7%/抽)，第80抽硬保底
 */
object GachaProbabilityModel {

    /**
     * 单抽五星概率配置
     */
    data class PityConfig(
        val baseRate: Double,       // 基础概率
        val softPityStart: Int,     // 软保底起始抽数（含）
        val softPityIncrement: Double, // 软保底每抽递增概率
        val hardPity: Int           // 硬保底抽数（必出五星）
    )

    // 角色池/常驻池/集录池配置（90 抽保底）
    private val CHARACTER_PITY_CONFIG = PityConfig(
        baseRate = 0.006,           // 0.6%
        softPityStart = 74,         // 第74抽开始软保底
        softPityIncrement = 0.06,   // 每抽 +6%
        hardPity = 90               // 第90抽硬保底
    )

    // 武器池配置（80 抽保底）
    private val WEAPON_PITY_CONFIG = PityConfig(
        baseRate = 0.007,           // 0.7%
        softPityStart = 63,         // 第63抽开始软保底
        softPityIncrement = 0.07,   // 每抽 +7%
        hardPity = 80               // 第80抽硬保底
    )

    /**
     * 获取指定卡池的概率配置
     */
    fun getPityConfig(poolType: Int): PityConfig {
        return when (poolType) {
            GachaType.CHARACTER.value,
            GachaType.CHARACTER_2.value,
            GachaType.STANDARD.value,
            GachaType.CHRONICLED.value -> CHARACTER_PITY_CONFIG
            GachaType.WEAPON.value -> WEAPON_PITY_CONFIG
            else -> CHARACTER_PITY_CONFIG
        }
    }

    /**
     * 计算第 n 抽单抽出五星的概率 P(X = n)
     * 即：前 n-1 抽都没出五星，第 n 抽出了五星
     *
     * @param n 抽数（从1开始）
     */
    fun pmf(n: Int, config: PityConfig): Double {
        if (n <= 0 || n > config.hardPity) return 0.0
        if (n == config.hardPity) {
            // 硬保底：前 hardPity-1 抽都没出，最后一抽必出
            return survival(config.hardPity - 1, config)
        }
        // 前 n-1 抽都没出 × 第 n 抽出的概率
        return survival(n - 1, config) * getRateAt(n, config)
    }

    /**
     * 计算累积分布函数 P(X ≤ n)
     * 即：在第 n 抽或之前获得五星的概率
     */
    fun cdf(n: Int, config: PityConfig): Double {
        if (n <= 0) return 0.0
        if (n >= config.hardPity) return 1.0
        return 1.0 - survival(n, config)
    }

    /**
     * 计算生存函数 P(X > n)
     * 即：前 n 抽都没有获得五星的概率
     */
    private fun survival(n: Int, config: PityConfig): Double {
        if (n <= 0) return 1.0
        if (n >= config.hardPity) return 0.0
        var result = 1.0
        for (i in 1..n) {
            result *= (1.0 - getRateAt(i, config))
        }
        return result
    }

    /**
     * 获取第 n 抽的单抽五星概率
     */
    private fun getRateAt(n: Int, config: PityConfig): Double {
        return when {
            n < config.softPityStart -> config.baseRate
            n >= config.hardPity -> 1.0
            else -> {
                val extra = (n - config.softPityStart + 1) * config.softPityIncrement
                (config.baseRate + extra).coerceAtMost(1.0)
            }
        }
    }

    /**
     * 计算理论期望出金抽数 E[X]
     */
    fun expectedValue(config: PityConfig): Double {
        var sum = 0.0
        for (n in 1..config.hardPity) {
            sum += n * pmf(n, config)
        }
        return sum
    }

    /**
     * 计算单次出金的运气分（百分位评分）
     *
     * 原理：运气分 = P(X > n) × 100
     * 即「比你更非的玩家比例」，分数越高运气越好。
     *
     * - n=1：P(X>1) ≈ 0.994 → 约 99 分（极欧）
     * - n=期望抽数：约 50 分（正常）
     * - n=90：P(X>90)=0 → 0 分（极非）
     *
     * @param pulls 本次出金用了多少抽（五星间隔）
     * @param poolType 卡池类型（决定概率模型）
     * @return 0~100 分，越高运气越好
     */
    fun calculateSingleLuckScore(pulls: Int, poolType: Int): Int {
        val config = getPityConfig(poolType)
        return calculateSingleLuckScore(pulls, config)
    }

    /**
     * 内部实现：使用指定配置计算单次运气分
     */
    fun calculateSingleLuckScore(pulls: Int, config: PityConfig): Int {
        if (pulls <= 0) return 100
        if (pulls >= config.hardPity) return 0
        // P(X > pulls) = 前 pulls 抽都没出五星的概率
        // 这代表「需要更多抽才能出金」的比例 = 比你更非的人
        val unluckierRatio = survival(pulls, config)
        // 钳制到 0~100
        return (unluckierRatio * 100).coerceIn(0.0, 100.0).toInt()
    }
}
