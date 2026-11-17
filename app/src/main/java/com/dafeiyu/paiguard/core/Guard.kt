package com.dafeiyu.paiguard.core

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 防护决策中心。
 *
 * 三道闸，逐层收紧：
 *
 *   [入口] Markdown 文本进来 → 静态扫描 → 公式太多就"断电"（打散分隔符，让解析器认不出来）
 *   [解析] TeXFormula 构造前 → 净化 + 限流 + 并发上限 → 超限就换成占位文本
 *   [兜底] 渲染线程抛 StackOverflowError → 吞掉、记账、熔断，别让线程池把进程带走
 *
 * 熔断（trip）是一个时间窗：默认 10 秒内所有公式一律不渲染，到点自动恢复。
 * 这样误杀一次不会让用户永久看不到公式，恶意刷屏也刷不动。
 */
internal object Guard {

    enum class Verdict { ALLOW, REWRITE, BLOCK }

    data class Decision(
        val verdict: Verdict,
        val text: String? = null,
        val reason: String? = null
    )

    // ---- 状态 ----

    @Volatile
    var trippedUntil: Long = 0L
        private set

    @Volatile
    var lastTripReason: String = ""
        private set

    private val windowStart = AtomicLong(0)
    private val windowCount = AtomicInteger(0)
    private val windowCost = AtomicInteger(0)

    private val trippedCount = AtomicInteger(0)
    private val blockedCount = AtomicInteger(0)

    @Volatile
    private var semaphore = Semaphore(2, true)

    @Volatile
    private var installed = false

    fun isTripped(): Boolean = Host.now() < trippedUntil

    fun stats(): Triple<Int, Int, Boolean> = Triple(trippedCount.get(), blockedCount.get(), isTripped())

    // ---- 入口层 ----

    /**
     * 文本进渲染管线之前调用。返回的 CharSequence 才是真正被渲染的内容。
     * 只在真的要改的时候才构造新字符串，避免给滚动列表增加 GC 压力。
     */
    fun inspectMarkdown(input: CharSequence): CharSequence {
        val cfg = Config.INSTANCE.current()
        if (!cfg.masterEnabled || !cfg.latexGuardEnabled) return input
        if (input.indexOf('$') < 0) return input

        val report = try {
            LatexScanner.scan(input)
        } catch (t: Throwable) {
            Reporter.log("扫描失败: ${t.message}")
            return input
        }
        if (!report.hasFormula) return input

        // 一个字都别渲染的极端情况
        if (cfg.killPlugin) return input

        // 熔断期内：连数都不用再数，直接打散分隔符，别让解析器有机会开工
        if (isTripped()) return neuterText(input.toString())

        return when {
            report.isHardBlocked(cfg) -> {
                trip(cfg, report.reason(cfg), report)
                val folded = neuter(input.toString(), report, cfg)
                Reporter.send(
                    Reporter.KIND_BLOCK,
                    "已拦截一条公式消息",
                    "原因：${report.reason(cfg)}\n" +
                        "公式数：${report.count}，最长：${report.maxChars} 字符，" +
                        "嵌套：${report.maxDepth}，最大尺寸：${report.maxDimensionEm}em\n" +
                        "危险宏：${report.dangerous.joinToString(" ").ifEmpty { "无" }}"
                )
                folded
            }

            report.isSwarm(cfg) -> {
                // 单看每一个都不致命，但一屏几十个照样能把 CPU 打满
                trip(cfg, report.reason(cfg), report)
                neuter(input.toString(), report, cfg)
            }

            account(report.cost, report.count, cfg) -> {
                // 单条都不超限，但一秒内连着来十几条（滚动 / 流式输出）也会积少成多
                trip(cfg, "1 秒内累计成本超出预算", report)
                neuter(input.toString(), report, cfg)
            }

            else -> input
        }
    }

    /** 只打断分隔符，不追加任何提示。用于熔断期内的静默降级。 */
    fun neuterText(src: String): String {
        if (src.indexOf('$') < 0) return src
        val sb = StringBuilder(src.length + 16)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            sb.append(c)
            if (c == '$' && i + 1 < src.length && src[i + 1] == '$') {
                sb.append('\u200B')
            }
            i++
        }
        return sb.toString()
    }

    /** 把 `$$` 拆开：中间插零宽空格，肉眼几乎看不出，解析器认不出来。 */
    fun neuter(src: String, report: LatexScanner.Report, cfg: GuardConfig): String {
        val sb = StringBuilder(neuterText(src))
        sb.append("\n\n—— PaiGuard：本条消息的 ")
            .append(report.count)
            .append(" 个公式已暂停渲染（")
            .append(report.reason(cfg))
            .append("），10 秒后自动恢复 ——")
        return sb.toString()
    }

    // ---- 解析层 ----

    /**
     * TeXFormula 构造前调用。
     * 注意：这里拿到的是"单个公式"的源码，已经脱离了消息上下文，
     * 所以只做单点判断（长度/嵌套/尺寸/危险宏），不做数量判断。
     */
    fun beforeFormula(latex: String): Decision {
        val cfg = Config.INSTANCE.current()
        if (!cfg.masterEnabled || !cfg.latexGuardEnabled) {
            return Decision(Verdict.ALLOW)
        }

        if (isTripped()) {
            blockedCount.incrementAndGet()
            return Decision(Verdict.BLOCK, reason = "熔断中：$lastTripReason")
        }

        if (latex.length > cfg.maxFormulaChars * 3) {
            // 连解析都不要解析，直接截断
            trip(cfg, "单公式 ${latex.length} 字符，超过上限 ${cfg.maxFormulaChars}", null)
            return Decision(Verdict.BLOCK, reason = "超长公式")
        }

        val sanitized = LatexSanitizer.sanitize(latex, cfg)
        if (sanitized.changed) {
            Reporter.log("净化: ${sanitized.actions.joinToString()} | ${latex.take(120)}")
            if (sanitized.text.length > cfg.maxFormulaChars) {
                return Decision(Verdict.REWRITE, LatexSanitizer.OMITTED, "净化后仍超长")
            }
            return Decision(Verdict.REWRITE, sanitized.text, sanitized.actions.joinToString())
        }

        return Decision(Verdict.ALLOW)
    }

    /** 并发闸：解析线程池默认是 cached，来多少算多少，这里硬压到 2 路。 */
    fun tryAcquireParseSlot(): Boolean = semaphore.tryAcquire()

    fun releaseParseSlot() {
        try {
            semaphore.release()
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun rebuildSemaphore(permits: Int) {
        semaphore = Semaphore(permits.coerceIn(1, 8), true)
    }

    /** 单次解析耗时过长：把这个公式拉黑，短时间别再碰它。 */
    fun afterFormula(latex: String, elapsedMs: Long, failed: Boolean) {
        val cfg = Config.INSTANCE.current()
        if (failed) {
            return
        }
        if (elapsedMs > 800L) {
            PoisonBook.remember(latex, "${elapsedMs}ms")
            trip(cfg, "单公式渲染 ${elapsedMs}ms", null)
        }
    }

    fun isPoisoned(latex: String): Boolean = PoisonBook.contains(latex)

    // ---- 兜底层 ----

    fun onFatal(t: Throwable, where: String) {
        val cfg = Config.INSTANCE.current()
        trippedCount.incrementAndGet()
        trippedUntil = Host.now() + cfg.tripMillis
        lastTripReason = "${t.javaClass.simpleName} @ $where"
        Reporter.send(
            Reporter.KIND_FATAL,
            "渲染线程致命错误已拦截",
            "位置：$where\n${t.javaClass.name}: ${t.message}\n" +
                t.stackTrace.take(12).joinToString("\n") { "  at $it" }
        )
        Reporter.log("致命错误 ${t.javaClass.name} @ $where")
    }

    // ---- 内部 ----

    private fun account(cost: Int, formulas: Int, cfg: GuardConfig): Boolean {
        val now = Host.now()
        val start = windowStart.get()
        if (now - start > 1000L) {
            if (windowStart.compareAndSet(start, now)) {
                windowCount.set(0)
                windowCost.set(0)
            }
        }
        val countAfter = windowCount.addAndGet(formulas)
        val costAfter = windowCost.addAndGet(cost)
        // 单条消息的阈值在 isSwarm 里已经判过；这里看的是"跨消息叠加"，
        // 所以放到 2 倍才动手，避免正常聊天被误伤。
        return countAfter > cfg.maxFormulasPerWindow * 2 || costAfter > cfg.softBudgetCost * 2
    }

    private fun trip(cfg: GuardConfig, reason: String, report: LatexScanner.Report?) {
        val now = Host.now()
        if (now < trippedUntil) return
        trippedUntil = now + cfg.tripMillis
        lastTripReason = reason
        trippedCount.incrementAndGet()

        Reporter.log("熔断: $reason（${cfg.tripMillis}ms）")
        Reporter.send(
            Reporter.KIND_TRIP,
            "已自动暂停公式渲染",
            "原因：$reason\n" +
                "公式数：${report?.count ?: 1}，持续 ${cfg.tripMillis / 1000} 秒后自动恢复",
            report?.count ?: 1
        )
        if (cfg.showToast) {
            Host.toast("PaiGuard：$reason，已暂停公式渲染")
        }
    }

    fun markInstalled() {
        installed = true
    }

    fun isInstalled(): Boolean = installed
}
