package com.genshin.gachahelper.auth

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DsSigner 单元测试
 *
 * 覆盖：
 * - DS1 格式与摘要自洽性（t,r,md5(salt&t&r)）
 * - DS2 格式与摘要自洽性（含 b / q 拼接规则）
 * - r 的取值范围与随机性
 * - salt 常量长度
 * - randomDeviceId 的 UUID v4 格式
 * - User-Agent 组装
 *
 * 说明：签名依赖当前时间戳与随机数，不做固定值断言，改为「按算法独立重算摘要并比对」的方式验证自洽性。
 */
class DsSignerTest {

    /** 测试侧独立实现的 md5，用于交叉验证被测实现的摘要 */
    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun split(ds: String): Triple<String, String, String> {
        val parts = ds.split(",")
        assertEquals("DS 签名应为 3 段（t,r,digest）", 3, parts.size)
        return Triple(parts[0], parts[1], parts[2])
    }

    // ==================== DS1 ====================

    @Test
    fun `DS1 摘要与算法定义自洽`() {
        val salt = DsSigner.Salt.K2
        val (t, r, digest) = split(DsSigner.generateDS1(salt))

        assertTrue("t 应为秒级时间戳", t.toLongOrNull() != null)
        assertTrue("t 应为 10 位秒级时间戳", t.length == 10 || t.length == 11)
        assertEquals("DS1 的 r 应为 6 位字母数字", 6, r.length)
        assertTrue("DS1 的 r 只允许字母数字", r.all { it.isLetterOrDigit() })
        assertEquals("摘要应为 md5(salt=..&t=..&r=..)", md5("salt=$salt&t=$t&r=$r"), digest)
    }

    @Test
    fun `DS1 使用不同 salt 产生不同摘要`() {
        val a = split(DsSigner.generateDS1(DsSigner.Salt.K2))
        val b = split(DsSigner.generateDS1(DsSigner.Salt.LK2))
        // t 秒级相同，salt 不同则摘要必然不同
        if (a.first == b.first) {
            assertTrue("salt 不同，摘要不应相同", a.third != b.third)
        }
    }

    @Test
    fun `DS1 连续两次调用的随机串不同`() {
        val r1 = split(DsSigner.generateDS1(DsSigner.Salt.K2)).second
        val r2 = split(DsSigner.generateDS1(DsSigner.Salt.K2)).second
        // 6 位 62 进制随机串，碰撞概率极低（62^6 ≈ 5.7e10）
        assertTrue("两次调用的 r 不应相同", r1 != r2)
    }

    // ==================== DS2 ====================

    @Test
    fun `DS2 无 body 无 query 时摘要自洽`() {
        val salt = DsSigner.Salt.X4
        val (t, r, digest) = split(DsSigner.generateDS2(salt))
        assertEquals(md5("salt=$salt&t=$t&r=$r"), digest)
    }

    @Test
    fun `DS2 携带 query 时按 q 拼接`() {
        val salt = DsSigner.Salt.X4
        val query = "uid=100000000&region=cn_gf01"
        val (t, r, digest) = split(DsSigner.generateDS2(salt, query = query))
        assertEquals(md5("salt=$salt&t=$t&r=$r&q=$query"), digest)
    }

    @Test
    fun `DS2 携带 body 时按 b 拼接且 b 在 q 之前`() {
        val salt = DsSigner.Salt.X6
        val body = """{"game_biz":"hk4e_cn"}"""
        val query = "uid=1"
        val (t, r, digest) = split(DsSigner.generateDS2(salt, body = body, query = query))
        assertEquals(md5("salt=$salt&t=$t&r=$r&b=$body&q=$query"), digest)
    }

    @Test
    fun `DS2 空白 body 与 query 不参与拼接`() {
        val salt = DsSigner.Salt.X4
        val blankBody = DsSigner.generateDS2(salt, body = "", query = "")
        val blankNowhere = DsSigner.generateDS2(salt, body = "   ", query = "  ")
        // 均为空则不追加 b / q 段
        val (t1, r1, d1) = split(blankBody)
        assertEquals(md5("salt=$salt&t=$t1&r=$r1"), d1)
        val (t2, r2, d2) = split(blankNowhere)
        assertEquals(md5("salt=$salt&t=$t2&r=$r2"), d2)
    }

    @Test
    fun `DS2 的 r 为六位数字`() {
        repeat(50) {
            val (_, r, _) = split(DsSigner.generateDS2(DsSigner.Salt.X4))
            assertEquals("DS2 的 r 应为 6 位", 6, r.length)
            assertTrue("DS2 的 r 应为纯数字", r.all { it.isDigit() })
            val v = r.toInt()
            // 常规区间为 100000..200000；实现中对 r == 100000 做了替换为 642367 的特例处理
            assertTrue(
                "r=$v 超出预期取值（100000..200000 或特例 642367）",
                v in 100000..200000 || v == 642367
            )
        }
    }

    @Test
    fun `DS2 摘要随 body 变化`() {
        val salt = DsSigner.Salt.X6
        val a = split(DsSigner.generateDS2(salt, body = "aaa"))
        val b = split(DsSigner.generateDS2(salt, body = "bbb"))
        if (a.first == b.first) {
            assertTrue("body 不同，摘要不应相同", a.third != b.third)
        }
    }

    // ==================== 常量与辅助 ====================

    @Test
    fun `salt 常量均为 32 位十六进制风格字符串`() {
        val salts = mapOf(
            "LK2" to DsSigner.Salt.LK2,
            "X4" to DsSigner.Salt.X4,
            "X6" to DsSigner.Salt.X6,
            "K2" to DsSigner.Salt.K2,
            "PROD" to DsSigner.Salt.PROD,
        )
        salts.forEach { (name, salt) ->
            assertEquals("salt $name 长度应为 32", 32, salt.length)
            assertTrue("salt $name 只应包含字母数字", salt.all { it.isLetterOrDigit() })
        }
    }

    @Test
    fun `USER_AGENT 携带米游社版本后缀`() {
        assertTrue(DsSigner.USER_AGENT.contains("miHoYoBBS/${DsSigner.Salt.APP_VERSION}"))
        assertTrue(DsSigner.USER_AGENT.startsWith("Mozilla/5.0"))
    }

    @Test
    fun `randomDeviceId 返回小写 UUID v4`() {
        val id = DsSigner.randomDeviceId()
        assertEquals("应为标准 UUID 长度", 36, id.length)
        assertEquals("应全为小写", id, id.lowercase())
        assertEquals("应为 v4 版本位", '4', id[14])
        assertTrue("variant 位应为 8/9/a/b 之一", id[19] in listOf('8', '9', 'a', 'b'))
        assertTrue("随机性校验：两次调用不应相同", DsSigner.randomDeviceId() != id)
    }
}
