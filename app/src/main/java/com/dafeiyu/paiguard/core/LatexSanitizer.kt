package com.dafeiyu.paiguard.core

/**
 * LaTeX 净化器：在源码交给 jlatexmath 解析之前动手。
 *
 * 原则是「降级而不是拒绝」——把人能看懂的公式留下，把只有 CPU 能看懂的宏去掉。
 * 直接 throw 会让 Markwon 走 errorHandler 显示一片空白，用户以为消息丢了。
 */
object LatexSanitizer {

    const val PLACEHOLDER = "\\text{[公式已由防护模块暂停渲染]}"
    const val OMITTED = "\\text{[公式过长，已省略]}"

    data class Result(
        val text: String,
        val changed: Boolean,
        val actions: List<String>
    )

    /** 定义型宏：整段删掉，连同它的参数一起。 */
    private val DEF_MACROS = listOf(
        "\\def", "\\gdef", "\\edef", "\\xdef",
        "\\newcommand", "\\renewcommand", "\\providecommand"
    )

    private val DIM_CMDS = listOf(
        "\\rule", "\\hspace", "\\vspace", "\\kern", "\\hskip",
        "\\mskip", "\\raisebox", "\\scalebox", "\\resizebox"
    )

    fun sanitize(raw: String, cfg: GuardConfig): Result {
        if (raw.isEmpty()) return Result(PLACEHOLDER, true, listOf("空公式"))

        var text = raw
        val actions = ArrayList<String>(3)

        if (cfg.blockDangerousMacros) {
            val stripped = stripDefinitions(text)
            if (stripped !== text) {
                actions.add("已移除宏定义")
                text = stripped
            }
            val noLet = stripLet(text)
            if (noLet !== text) {
                actions.add("已移除 \\let")
                text = noLet
            }
        }

        val clamped = clampDimensions(text, cfg.maxRuleEm)
        if (clamped !== text) {
            actions.add("已裁剪超大尺寸")
            text = clamped
        }

        if (text.length > cfg.maxFormulaChars * 2) {
            text = truncateBalanced(text, cfg.maxFormulaChars)
            actions.add("已截断超长公式")
        }

        if (text.isBlank()) {
            return Result(PLACEHOLDER, true, actions + "内容为空")
        }
        return Result(text, actions.isNotEmpty(), actions)
    }

    /** 删除 `\def\a{...}` / `\newcommand{\a}[1]{...}` 这类定义。 */
    private fun stripDefinitions(src: String): String {
        var out = src
        for (macro in DEF_MACROS) {
            var idx = out.indexOf(macro)
            while (idx >= 0) {
                val end = definitionEnd(out, idx, macro)
                if (end <= idx) {
                    idx = out.indexOf(macro, idx + macro.length)
                    continue
                }
                out = out.substring(0, idx) + out.substring(end)
                idx = out.indexOf(macro, idx)
            }
        }
        return out
    }

    /**
     * 从定义宏的起始位置算到定义结束。
     * `\def\a#1{...}` 到最后一个参数组的右括号；`\newcommand{\a}[1][x]{...}` 同理。
     */
    private fun definitionEnd(s: String, start: Int, macro: String): Int {
        var i = start + macro.length
        // 命令名：\foo 或 {\foo}
        while (i < s.length && s[i] == ' ') i++
        if (i < s.length && s[i] == '{') {
            val close = matchBrace(s, i)
            if (close < 0) return -1
            i = close + 1
        } else if (i < s.length && s[i] == '\\') {
            i++
            while (i < s.length && s[i].isLetter()) i++
        }
        // 参数个数/默认值
        var guard = 0
        while (i < s.length && guard < 8) {
            guard++
            if (s[i] == '[') {
                val close = s.indexOf(']', i)
                if (close < 0) return -1
                i = close + 1
            } else if (s[i] == '#') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            } else {
                break
            }
        }
        // 函数体
        while (i < s.length && s[i] == ' ') i++
        if (i < s.length && s[i] == '{') {
            val close = matchBrace(s, i)
            return if (close < 0) -1 else close + 1
        }
        // \def\a#1\endcsname 之类的怪写法：删到行尾
        val eol = s.indexOf('\n', i)
        return if (eol < 0) s.length else eol
    }

    /** `\let\a\b` 只需要删两个 token，但直接删掉整条语句最省事。 */
    private fun stripLet(src: String): String {
        var out = src
        var idx = out.indexOf("\\let")
        while (idx >= 0) {
            var i = idx + 4
            var tokens = 0
            while (i < out.length && tokens < 2) {
                while (i < out.length && out[i] == ' ') i++
                if (i < out.length && out[i] == '\\') {
                    i++
                    while (i < out.length && out[i].isLetter()) i++
                    tokens++
                } else if (i < out.length && (out[i] == '=' || out[i] == ' ')) {
                    i++
                } else {
                    i++
                    tokens++
                }
            }
            out = out.substring(0, idx) + out.substring(i)
            idx = out.indexOf("\\let", idx)
        }
        return out
    }

    /** 尺寸裁剪：`\rule{500pt}{500pt}` → `\rule{40em}{40em}`。 */
    private fun clampDimensions(src: String, maxEm: Int): String {
        var out = src
        for (cmd in DIM_CMDS) {
            var idx = out.indexOf(cmd)
            while (idx >= 0) {
                var j = idx + cmd.length
                if (j < out.length && out[j] == '*') j++
                while (j < out.length && out[j] == '[') {
                    val close = out.indexOf(']', j)
                    if (close < 0) break
                    j = close + 1
                }
                var groups = 0
                var wroteTo = j
                val sb = StringBuilder()
                var k = j
                while (k < out.length && out[k] == '{' && groups < 2) {
                    val close = matchBrace(out, k)
                    if (close < 0) break
                    val arg = out.substring(k + 1, close)
                    val clamped = clampOne(arg, maxEm)
                    sb.append('{').append(clamped).append('}')
                    k = close + 1
                    groups++
                    wroteTo = k
                }
                if (sb.isNotEmpty()) {
                    out = out.substring(0, j) + sb + out.substring(wroteTo)
                    idx = out.indexOf(cmd, idx + 1)
                } else {
                    idx = out.indexOf(cmd, idx + 1)
                }
            }
        }
        return out
    }

    private val NUM_UNIT = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*(pt|em|ex|mm|cm|in|bp|pc|px|sp)?\s*$""")

    private fun clampOne(arg: String, maxEm: Int): String {
        val m = NUM_UNIT.matchEntire(arg) ?: return arg
        val v = m.groupValues[1].toFloatOrNull() ?: return arg
        val unit = m.groupValues[2].ifEmpty { "em" }
        val em = when (unit) {
            "pt", "bp" -> v / 10f
            "pc" -> v * 1.2f
            "mm" -> v * 2.85f
            "cm" -> v * 28.5f
            "in" -> v * 72f
            "px" -> v / 16f
            "sp" -> v / 655360f
            else -> v
        }
        if (em <= maxEm) return arg
        return "${maxEm}em"
    }

    /** 截断但不破坏括号平衡，避免直接抛 ParseException。 */
    fun truncateBalanced(src: String, limit: Int): String {
        if (src.length <= limit) return src
        var depth = 0
        var i = 0
        val cut = StringBuilder()
        while (i < src.length && cut.length < limit) {
            val c = src[i]
            if (c == '\\') {
                if (i + 1 < src.length && cut.length + 2 <= limit) {
                    cut.append(c).append(src[i + 1])
                }
                i += 2
                continue
            }
            if (c == '{') depth++
            if (c == '}') {
                if (depth == 0) {
                    // 多出来的右括号直接丢掉，防止破坏结构
                    i++
                    continue
                }
                depth--
            }
            cut.append(c)
            i++
        }
        repeat(depth) { cut.append('}') }
        cut.append("\\cdots")
        return cut.toString()
    }

    fun matchBrace(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }
}
