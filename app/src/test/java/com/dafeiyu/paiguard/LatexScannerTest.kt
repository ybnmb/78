package com.dafeiyu.paiguard

import com.dafeiyu.paiguard.core.GuardConfig
import com.dafeiyu.paiguard.core.LatexScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexScannerTest {

    private val cfg = GuardConfig()

    @Test
    fun `普通公式不算异常`() {
        val r = LatexScanner.scan("求导：$$\\frac{d}{dx}x^2 = 2x$$")
        assertEquals(1, r.count)
        assertFalse(r.isHardBlocked(cfg))
        assertFalse(r.isSwarm(cfg))
    }

    @Test
    fun `没有公式直接放过`() {
        val r = LatexScanner.scan("今天吃什么？$$ 只有半个分隔符")
        assertEquals(0, r.count)
    }

    @Test
    fun `刷屏公式判定为集群攻击`() {
        val text = buildString {
            repeat(30) { append("$$\nx_$it = $it^2\n$$\n") }
        }
        val r = LatexScanner.scan(text)
        assertEquals(30, r.count)
        assertTrue(r.isSwarm(cfg))
    }

    @Test
    fun `递归宏被识别`() {
        val r = LatexScanner.scan("$$\\def\\x{\\x}\\x$$")
        assertTrue(r.dangerous.contains("\\def"))
        assertTrue(r.isHardBlocked(cfg))
    }

    @Test
    fun `尺寸炸弹被识别`() {
        val r = LatexScanner.scan("$$\\rule{9999pt}{9999pt}$$")
        assertTrue("maxDimensionEm=${r.maxDimensionEm}", r.maxDimensionEm > 900f)
        assertTrue(r.isSingleShotLethal(cfg))
    }

    @Test
    fun `嵌套深度可计算`() {
        val r = LatexScanner.scan("$$\\frac{\\frac{\\frac{a}{b}}{c}}{d}$$")
        assertTrue("depth=${r.maxDepth}", r.maxDepth >= 3)
    }

    @Test
    fun `超长公式触发单点致命`() {
        val long = "$$" + "x+".repeat(2000) + "1$$"
        val r = LatexScanner.scan(long)
        assertTrue(r.maxChars > cfg.maxFormulaChars)
        assertTrue(r.isSingleShotLethal(cfg))
    }

    @Test
    fun `转义花括号不增加深度`() {
        assertEquals(0, LatexScanner.depthOf("\\{a\\}"))
    }

    @Test
    fun `多行公式块被正确切分`() {
        val text = "前文\n$$\na=b\n$$\n中间\n$$\nc=d\n$$\n后文"
        val r = LatexScanner.scan(text)
        assertEquals(2, r.count)
        assertTrue(r.formulas.all { it.block })
    }
}
