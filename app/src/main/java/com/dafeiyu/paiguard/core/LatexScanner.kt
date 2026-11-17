package com.dafeiyu.paiguard.core

/**
 * LaTeX 源码静态扫描器。
 *
 * 不依赖 Android，不依赖 Xposed，纯字符串分析 —— 可以单独跑单元测试，
 * 也可以在 PC 端拿群众举报的样本先跑一遍看会不会误杀。
 *
 * 它要回答三个问题：
 * 1. 这条消息里有多少个公式？（数量维度：刷屏）
 * 2. 最狠的那个有多狠？（单点维度：一个公式就能把 CPU 打死）
 * 3. 有没有构造递归/巨物的宏？（性质维度：不是"大"，是"恶意"）
 */
object LatexScanner {

    /** 危险宏：能定义新命令、能无限展开、能读文件、能画巨物。 */
    private val DANGEROUS = listOf(
        "\\def", "\\gdef", "\\edef", "\\xdef",
        "\\newcommand", "\\renewcommand", "\\providecommand",
        "\\let", "\\futurelet", "\\csname", "\\expandafter",
        "\\input", "\\include", "\\includeonly",
        "\\openout", "\\write", "\\read", "\\catcode",
        "\\loop", "\\repeat", "\\romannumeral",
        "\\afterassignment", "\\aftergroup"
    )

    /** 这些命令自带尺寸参数，是"一个公式占 500 屏"的常见来源。 */
    private val DIMENSION_CMDS = listOf(
        "\\rule", "\\hspace", "\\vspace", "\\kern", "\\hskip",
        "\\mskip", "\\raisebox", "\\scalebox", "\\resizebox"
    )

    private val DIM_UNIT = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*(pt|em|ex|mm|cm|in|bp|pc|px|sp)?\s*$""")

    data class Formula(
        val start: Int,
        val end: Int,
        val body: String,
        val block: Boolean
    )

    data class Report(
        val count: Int,
        val maxChars: Int,
        val maxDepth: Int,
        val maxDimensionEm: Float,
        val dangerous: List<String>,
        val cost: Int,
        val formulas: List<Formula>
    ) {
        val hasFormula: Boolean get() = count > 0

        /** 单点致命：一条消息里出现一个就能拖死渲染线程。 */
        fun isSingleShotLethal(cfg: GuardConfig): Boolean =
            maxChars > cfg.maxFormulaChars ||
                maxDepth > cfg.maxNestingDepth ||
                maxDimensionEm > cfg.maxRuleEm

        /** 集群致命：公式多到会把一屏变成炼丹炉。 */
        fun isSwarm(cfg: GuardConfig): Boolean =
            count >= cfg.maxFormulasPerWindow || cost > cfg.softBudgetCost

        fun isHardBlocked(cfg: GuardConfig): Boolean =
            count >= cfg.hardFormulaLimit || isSingleShotLethal(cfg) ||
                (cfg.blockDangerousMacros && dangerous.isNotEmpty())

        fun reason(cfg: GuardConfig): String = when {
            count >= cfg.hardFormulaLimit -> "公式数量 $count ≥ ${cfg.hardFormulaLimit}"
            maxChars > cfg.maxFormulaChars -> "单公式 ${maxChars} 字符 > ${cfg.maxFormulaChars}"
            maxDepth > cfg.maxNestingDepth -> "嵌套深度 $maxDepth > ${cfg.maxNestingDepth}"
            maxDimensionEm > cfg.maxRuleEm -> "尺寸 ${maxDimensionEm}em > ${cfg.maxRuleEm}em"
            dangerous.isNotEmpty() -> "危险宏 ${dangerous.joinToString(" ") { it.trimStart('\\') }}"
            count >= cfg.maxFormulasPerWindow -> "本窗口公式 $count ≥ ${cfg.maxFormulasPerWindow}"
            else -> "累计成本 $cost"
        }
    }

    private val EMPTY = Report(0, 0, 0, 0f, emptyList(), 0, emptyList())

    fun scan(text: CharSequence?): Report {
        if (text.isNullOrEmpty()) return EMPTY
        if (text.indexOf('$') < 0) return EMPTY

        val src = text.toString()
        val formulas = extract(src)
        if (formulas.isEmpty()) return EMPTY

        var maxChars = 0
        var maxDepth = 0
        var maxDim = 0f
        val hit = LinkedHashSet<String>()
        var cost = 0

        for (f in formulas) {
            maxChars = maxOf(maxChars, f.body.length)
            val depth = depthOf(f.body)
            maxDepth = maxOf(maxDepth, depth)
            val dim = maxDimensionEm(f.body)
            maxDim = maxOf(maxDim, dim)

            for (d in DANGEROUS) {
                if (f.body.contains(d)) hit.add(d)
            }

            cost += costOf(f.body, depth)
        }

        return Report(
            count = formulas.size,
            maxChars = maxChars,
            maxDepth = maxDepth,
            maxDimensionEm = maxDim,
            dangerous = hit.toList(),
            cost = cost,
            formulas = formulas
        )
    }

    /**
     * 抽取 `$$...$$` 片段。元宝派用的是 Markwon ext-latex：
     * block 形态是行首 `$$` 起、行首 `$$` 止；inline 形态是同行一对 `$$`。
     * 两者都可以用"两两配对"覆盖，配对失败（落单）就当作没闭合、不计入。
     */
    private fun extract(src: String): List<Formula> {
        val out = ArrayList<Formula>()
        var i = 0
        var open = -1
        var openBlock = false

        while (i < src.length - 1) {
            if (src[i] == '$' && src[i + 1] == '$') {
                if (open < 0) {
                    open = i
                    openBlock = isLineHead(src, i)
                    i += 2
                    continue
                }
                if (i > open + 2) {
                    out.add(
                        Formula(
                            start = open,
                            end = i + 2,
                            body = src.substring(open + 2, i),
                            block = openBlock
                        )
                    )
                }
                open = -1
                i += 2
                continue
            }
            i++
        }
        return out
    }

    private fun isLineHead(src: String, idx: Int): Boolean {
        var j = idx - 1
        while (j >= 0 && (src[j] == ' ' || src[j] == '\t')) j--
        return j < 0 || src[j] == '\n' || src[j] == '\r'
    }

    /** 花括号嵌套深度（跳过 `\{` `\}` 与 `\$`）。 */
    fun depthOf(body: String): Int {
        var depth = 0
        var max = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '{') {
                depth++
                if (depth > max) max = depth
            } else if (c == '}') {
                if (depth > 0) depth--
            }
            i++
        }
        return max
    }

    /** 找出公式里最大的尺寸参数（折算成 em）。 */
    fun maxDimensionEm(body: String): Float {
        var max = 0f
        var i = 0
        while (i < body.length) {
            if (body[i] != '\\') {
                i++
                continue
            }
            val cmd = DIMENSION_CMDS.firstOrNull { body.startsWith(it, i) }
            if (cmd == null) {
                i++
                continue
            }
            var j = i + cmd.length
            // 跳过星号与可选参数
            if (j < body.length && body[j] == '*') j++
            while (j < body.length && body[j] == '[') {
                val close = body.indexOf(']', j)
                if (close < 0) break
                j = close + 1
            }
            // 连续读取最多的两组 {...} 参数
            var groups = 0
            while (j < body.length && body[j] == '{' && groups < 2) {
                val close = matchBrace(body, j)
                if (close < 0) break
                val arg = body.substring(j + 1, close)
                max = maxOf(max, toEm(arg))
                j = close + 1
                groups++
            }
            i = maxOf(j, i + 1)
        }
        return max
    }

    private fun matchBrace(s: String, open: Int): Int {
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

    private fun toEm(raw: String): Float {
        val m = DIM_UNIT.matchEntire(raw) ?: return 0f
        val v = m.groupValues[1].toFloatOrNull() ?: return 0f
        return when (m.groupValues[2]) {
            "pt" -> v / 10f
            "bp" -> v / 10f
            "pc" -> v * 1.2f
            "mm" -> v * 2.85f
            "cm" -> v * 28.5f
            "in" -> v * 72f
            "px" -> v / 16f
            "sp" -> v / 655360f
            else -> v // em / ex / 无单位：按 em 算
        }
    }

    /** 粗略成本模型：字符数打底，结构命令加权，深度指数惩罚。 */
    private fun costOf(body: String, depth: Int): Int {
        var structural = 0
        structural += body.countOccurrences("\\frac") * 40
        structural += body.countOccurrences("\\sqrt") * 30
        structural += body.countOccurrences("\\begin") * 80
        structural += body.countOccurrences("\\sum") * 20
        structural += body.countOccurrences("\\int") * 20
        structural += body.countOccurrences("\\left") * 10
        val depthPenalty = if (depth <= 1) 0 else (depth - 1) * (depth - 1) * 12
        return body.length + structural + depthPenalty + 30
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var idx = indexOf(needle)
        while (idx >= 0) {
            count++
            idx = indexOf(needle, idx + needle.length)
        }
        return count
    }
}
