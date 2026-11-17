package com.dafeiyu.paiguard.hooks

import com.dafeiyu.paiguard.core.Config
import com.dafeiyu.paiguard.core.Guard
import com.dafeiyu.paiguard.core.Reporter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 最后一道闸：线程级兜底。
 *
 * LaTeX 渲染跑在 Markwon 的线程池里，一个 `\def\x{\x}\x` 就能把线程栈打穿，
 * 抛出来的是 StackOverflowError —— 注意是 Error 不是 Exception，绝大多数
 * `catch (Exception)` 抓不到它。它冒到线程顶，就走 UncaughtExceptionHandler，
 * Android 那边接着就 `Process.killProcess` 了。
 *
 * 我们在 ThreadGroup.uncaughtException 这一层截住：如果异常栈里有 jlatexmath /
 * markwon / TeXParser，就吞掉并记账。不是我们的锅不吞。
 */
internal object CrashHook {

    private val LATEX_TRACES = arrayOf(
        "jlatexmath",
        "TeXParser",
        "TeXFormula",
        "markwon",
        "JLatexMath"
    )

    fun install(cl: ClassLoader) {
        try {
            XposedBridge.hookAllMethods(
                ThreadGroup::class.java,
                "uncaughtException",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val t = param.args.getOrNull(1) as? Throwable ?: return
                        val cfg = Config.INSTANCE.current()
                        if (!cfg.masterEnabled || !cfg.swallowLatexFatal) return

                        val isStackOverflow = t is StackOverflowError
                        val isOom = t is OutOfMemoryError
                        if (!isStackOverflow && !isOom) return

                        if (!looksLikeLatex(t)) return

                        Guard.onFatal(t, "ThreadGroup.uncaughtException")

                        if (isStackOverflow) {
                            // 栈溢出对进程是"可恢复"的：这个线程废了，主线程没事。
                            // 吞掉，避免整机重启 App —— 用户只是看个群聊。
                            param.result = null
                            Reporter.log("已拦截 LaTeX StackOverflowError，进程保住了")
                        } else {
                            // OOM 不吞：内存已经见底，继续跑更容易出现半渲染的鬼状态。
                            // 只记录现场，并立刻熔断，让后续公式不再往里砸。
                            Reporter.log("LaTeX 相关 OOM，已熔断但不再拦截")
                        }
                    }
                }
            )
            Reporter.log("已挂载 ThreadGroup.uncaughtException 兜底")
        } catch (t: Throwable) {
            Reporter.log("挂载崩溃兜底失败: ${t.message}")
        }
    }

    private fun looksLikeLatex(t: Throwable): Boolean {
        val frames = t.stackTrace
        if (frames.isEmpty()) return false
        for (f in frames) {
            val cls = f.className
            for (key in LATEX_TRACES) {
                if (cls.contains(key, ignoreCase = true)) return true
            }
            // Markwon 的线程池命名一般是 pool-N-thread-M，栈里看不到，
            // 但总能看到调用链；若连调用链都没留下，宁可放过不误吞。
        }
        return false
    }
}
