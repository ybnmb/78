package com.dafeiyu.paiguard.hooks

import com.dafeiyu.paiguard.core.Config
import com.dafeiyu.paiguard.core.Guard
import com.dafeiyu.paiguard.core.LatexSanitizer
import com.dafeiyu.paiguard.core.Reporter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 第三道闸：`org.scilab.forge.jlatexmath.TeXFormula` 的构造。
 *
 * 这里是所有 LaTeX 的必经之路——不管上游怎么绕（Markwon、JLatexMathView、
 * 第三方富文本、甚至直接 new），最后都要 new 一个 TeXFormula 才能算出排版。
 * 所以在这层做净化与限流，绕过不了。
 *
 * 顺便把字号压到 64 以内：`\rule{9999pt}{9999pt}` 配超大字号会直接
 * 申请一张几万像素的 Bitmap，那不是卡，是闪退。
 */
internal object RenderHook {

    private const val FORMULA = "org.scilab.forge.jlatexmath.TeXFormula"
    private const val JLATEXMATH = "ru.noties.jlatexmath.JLatexMath"

    private const val T0_KEY = "paiguard_t0"
    private const val SLOT_KEY = "paiguard_slot"
    private const val ORIGIN_KEY = "paiguard_origin"

    private const val MAX_TEXT_SIZE = 64f

    fun install(cl: ClassLoader) {
        hookFormula(cl)
        hookTextSize(cl)
    }

    private fun hookFormula(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(FORMULA, cl)
        if (cls == null) {
            Reporter.log("未找到 $FORMULA")
            return
        }

        try {
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val idx = param.args.indexOfFirst { it is String }
                    if (idx < 0) return

                    val raw = param.args[idx] as? String ?: return
                    if (raw.isEmpty()) return

                    val cfg = Config.INSTANCE.current()
                    if (!cfg.masterEnabled || !cfg.latexGuardEnabled) return

                    // 已经判过毒的，直接换成占位
                    if (Guard.isPoisoned(raw)) {
                        param.args[idx] = LatexSanitizer.PLACEHOLDER
                        Reporter.log("命中毒公式指纹，跳过解析")
                        return
                    }

                    val decision = Guard.beforeFormula(raw)
                    when (decision.verdict) {
                        Guard.Verdict.BLOCK -> {
                            param.args[idx] = LatexSanitizer.PLACEHOLDER
                            return
                        }

                        Guard.Verdict.REWRITE -> {
                            param.args[idx] = decision.text ?: LatexSanitizer.PLACEHOLDER
                        }

                        Guard.Verdict.ALLOW -> {
                            if (Guard.tryAcquireParseSlot()) {
                                param.setObjectExtra(SLOT_KEY, true)
                            } else {
                                param.args[idx] = LatexSanitizer.PLACEHOLDER
                                return
                            }
                        }
                    }

                    param.setObjectExtra(ORIGIN_KEY, raw)
                    param.setObjectExtra(T0_KEY, System.nanoTime())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val slot = param.getObjectExtra(SLOT_KEY)
                    if (slot == true) Guard.releaseParseSlot()

                    val t0 = param.getObjectExtra(T0_KEY) as? Long ?: return
                    val elapsed = (System.nanoTime() - t0) / 1_000_000L
                    val origin = param.getObjectExtra(ORIGIN_KEY) as? String ?: return

                    if (param.hasThrowable()) {
                        val t = param.throwable
                        // 普通 ParseException 交给 Markwon 的 errorHandler 处理；
                        // 只有 Error 级别才是"能带走进程"的那一类，需要记账并熔断。
                        if (t is StackOverflowError || t is OutOfMemoryError) {
                            Guard.onFatal(t, "TeXFormula(${origin.length} chars)")
                        }
                        return
                    }
                    Guard.afterFormula(origin, elapsed, failed = false)
                }
            })
            Reporter.log("已挂载 $FORMULA 全部构造函数")
        } catch (t: Throwable) {
            Reporter.log("挂载 $FORMULA 失败: ${t.message}")
        }
    }

    /**
     * 限制渲染字号。找 `JLatexMath` 里所有"吃 TeXFormula 的 float 参数"的方法，
     * 不写死方法名，避免不同版本对不上。
     */
    private fun hookTextSize(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(JLATEXMATH, cl) ?: return
        var count = 0
        try {
            for (m in cls.declaredMethods) {
                val touchesFormula = m.parameterTypes.any { it.name.contains("TeXFormula") }
                val hasFloat = m.parameterTypes.any { it == Float::class.javaPrimitiveType || it == java.lang.Float::class.java }
                if (!touchesFormula || !hasFloat) continue

                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (i in param.args.indices) {
                            val a = param.args[i]
                            if (a is Float && a > MAX_TEXT_SIZE) {
                                param.args[i] = MAX_TEXT_SIZE
                            }
                        }
                    }
                })
                count++
            }
            if (count > 0) Reporter.log("已挂载 $JLATEXMATH 字号限制 x$count")
        } catch (t: Throwable) {
            Reporter.log("挂载字号限制失败: ${t.message}")
        }
    }
}
