package com.dafeiyu.paiguard

import com.dafeiyu.paiguard.core.GuardConfig
import com.dafeiyu.paiguard.core.LatexSanitizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexSanitizerTest {

    private val cfg = GuardConfig()

    @Test
    fun `普通公式原样保留`() {
        val src = "\\frac{a}{b} + \\sqrt{2}"
        val r = LatexSanitizer.sanitize(src, cfg)
        assertFalse(r.changed)
        assertTrue(r.text == src)
    }

    @Test
    fun `递归宏定义被移除`() {
        val src = "\\def\\x{\\x}\\x"
        val r = LatexSanitizer.sanitize(src, cfg)
        assertTrue(r.changed)
        assertFalse(r.text.contains("\\def"))
    }

    @Test
    fun `newcommand 连同参数一起移除`() {
        val src = "\\newcommand{\\vect}[1]{\\mathbf{#1}}\\vect{v}"
        val r = LatexSanitizer.sanitize(src, cfg)
        assertFalse(r.text.contains("\\newcommand"))
        assertTrue(r.text.contains("\\vect{v}"))
    }

    @Test
    fun `超大尺寸被裁剪`() {
        val src = "\\rule{9999pt}{9999pt}"
        val r = LatexSanitizer.sanitize(src, cfg)
        assertTrue(r.changed)
        assertFalse(r.text.contains("9999pt"))
    }

    @Test
    fun `截断不破坏括号平衡`() {
        val src = "\\frac{" + "a".repeat(5000) + "}{b}"
        val out = LatexSanitizer.truncateBalanced(src, 100)
        assertTrue(out.length < 200)
        val opens = out.count { it == '{' }
        val closes = out.count { it == '}' }
        assertTrue("opens=$opens closes=$closes", closes >= opens)
    }

    @Test
    fun `清空后的空公式给占位符`() {
        val r = LatexSanitizer.sanitize("\\def\\x{}", cfg)
        assertNotEquals("", r.text)
        assertTrue(r.text.contains("text"))
    }

    @Test
    fun `关闭危险宏拦截时不动源码`() {
        val relaxed = GuardConfig(blockDangerousMacros = false)
        val r = LatexSanitizer.sanitize("\\def\\x{a}", relaxed)
        assertTrue(r.text.contains("\\def"))
    }
}
