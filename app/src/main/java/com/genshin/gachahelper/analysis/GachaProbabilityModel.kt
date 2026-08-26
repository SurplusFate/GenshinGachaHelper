package com.genshin.gachahelper.analysis

import com.genshin.gachahelper.data.model.GachaType
import kotlin.math.pow

/**
 * 原神抽卡概率模型
 *
 * 基于官方公布的概率和实测数据，计算单次出金的概率分布，
 * 用于运气评分系统。
 *
 * 角色池/常驻池/集录池（90 抽保底）：
 * - 1~73 抽：基础概率 0.6%
 * - 74~89 抽：软保底，概率逐抽递增
 * - 第 90 抽：硬保底，概率 100%
 *
 * 武器池（80 抽保底）：
 * - 1~62 抽：基础概率 0.7%
 * - 63~79 抽：软保底，概率逐抽递增
 * - 第 80 抽：硬保底，概率 100%
 */
object GachaProbabilityModel {

    /**
     * 保底配置
     */
    private data class PityConfig(
        val hardPity: Int,           // 硬保底抽数
        val baseRate: Double,        // 基础概率（小数，如 0.006 = 0.6%）
        val softPityStart: Int,      // 软保底起始抽数
        val softPityIncrement: Double // 软保底每抽增加的概率
    )

    private val characterConfig = PityConfig(
        hardPity = 90,
        baseRate = 0.006,
        softPityStart = 74,
        softPityIncrement = 0.06
    )

    private val weaponConfig = PityConfig(
        hardPity = 80,
        baseRate = 0.007,
        softPityStart = 63,
        softPityIncrement = 0.07
    )

    private val standardConfig = characterConfig
    private val chronicledConfig = characterConfig
    private val noviceConfig = PityConfig(
        hardPity = 20,
        baseRate = 0.0, // 新手池无五星保底概念
        softPityStart = 20,
        softPityIncrement = 0.0
    )

    private fun getPityConfig(poolType: Int): PityConfig {
        return when (poolType) {
            GachaType.CHARACTER.value -> characterConfig
            GachaType.CHARACTER_2.value -> characterConfig
            GachaType.WEAPON.value -> weaponConfig
            GachaType.STANDARD.value -> standardConfig
            GachaType.NOVICE.value -> noviceConfig
            GachaType.CHRONICLED.value -> chronicledConfig
            else -> characterConfig
        }
    }

    /**
     * 计算第 n 抽出五星的概率（PMF：概率质量函数）
     *
     * P(X = n) = 前 n-1 抽都没出 × 第 n 抽出了
     */
    fun pmf(n: Int, poolType: Int): Double {
        return pmf(n, getPityConfig(poolType))
    }

    private fun pmf(n: Int, config: PityConfig): Double {
        if (n <= 0) return 0.0
        if (n > config.hardPity) return 0.0
        if (n == config.hardPity) {
            // 硬保底：前 hardPity-1 抽都没出的概率
            return survival(config.hardPity - 1, config)
        }
        // P(X=n) = P(X>n-1) × p_n
        return survival(n - 1, config) * getRateAt(n, config)
    }

    /**
     * 计算 n 抽及之前出五星的概率（CDF：累积分布函数）
     *
     * P(X ≤ n) = Σ P(X = k) for k = 1..n
     */
    fun cdf(n: Int, poolType: Int): Double {
        return cdf(n, getPityConfig(poolType))
    }

    private fun cdf(n: Int, config: PityConfig): Double {
        if (n <= 0) return 0.0
        if (n >= config.hardPity) return 1.0
        return 1.0 - survival(n, config)
    }

    /**
     * 生存函数：计算 n 抽还没出五星的概率
     *
     * P(X > n) = Π (1 - p_k) for k = 1..n
     */
    fun survival(n: Int, poolType: Int): Double {
        return survival(n, getPityConfig(poolType))
    }

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
     * 获取第 n 抽的出五星概率
     */
    private fun getRateAt(n: Int, config: PityConfig): Double {
        if (n <= 0) return 0.0
        if (n >= config.hardPity) return 1.0
        return if (n < config.softPityStart) {
            config.baseRate
        } else {
            val softPityPulls = n - config.softPityStart + 1
            (config.baseRate + softPityPulls * config.softPityIncrement).coerceAtMost(1.0)
        }
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
     */
    fun calculateSingleLuckScore(pulls: Int, poolType: Int): Int {
        val config = getPityConfig(poolType)
        return calculateSingleLuckScore(pulls, config)
    }

    private fun calculateSingleLuckScore(pulls: Int, config: PityConfig): Int {
        if (pulls <= 0) return 100
        if (pulls >= config.hardPity) return 0
        // P(X > pulls) = 前 pulls 抽都没出五星的概率
        // 这代表「需要更多抽才能出金」的比例 = 比你更非的人
        val unluckierRatio = survival(pulls, config)
        // 钳制到 0~100
        return (unluckierRatio * 100).coerceIn(0.0, 100.0).toInt()
    }

    /**
     * 计算期望出金抽数
     */
    fun expectedPulls(poolType: Int): Double {
        val config = getPityConfig(poolType)
        var sum = 0.0
        for (i in 1..config.hardPity) {
            sum += i * pmf(i, config)
        }
        return sum
    }
}
