package com.dafeiyu.paiguard.core

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

/**
 * 配置：模块 App 写，宿主进程读。
 *
 * 走 XSharedPreferences 跨进程读模块自己的私有 prefs 文件（LSPosed 环境下可用）。
 * 任何一步失败都退回 [GuardConfig] 的默认值——防护工具本身绝不允许因为读配置失败而崩宿主。
 */
class Config private constructor() {

    @Volatile
    var value: GuardConfig = GuardConfig()
        private set

    private var prefs: XSharedPreferences? = null
    private var lastLoad: Long = 0L

    fun attach(modulePackage: String, prefName: String) {
        prefs = try {
            XSharedPreferences(modulePackage, prefName)
        } catch (t: Throwable) {
            XposedBridge.log("[PaiGuard] XSharedPreferences 不可用: ${t.message}")
            null
        }
        reload(force = true)
    }

    /** 宿主每次判定前调用；最多 1 秒读一次盘，避免频繁 IO。 */
    fun current(): GuardConfig {
        val now = System.currentTimeMillis()
        if (now - lastLoad > 1000L) reload(force = false)
        return value
    }

    private fun reload(force: Boolean) {
        lastLoad = System.currentTimeMillis()
        val p = prefs ?: return
        try {
            p.reload()
        } catch (_: Throwable) {
            // 旧版本 Android 上 reload 可能抛，忽略并继续用上次的值
        }
        value = try {
            GuardConfig(
                masterEnabled = p.getBoolean(PrefKeys.MASTER, true),
                latexGuardEnabled = p.getBoolean(PrefKeys.LATEX_GUARD, true),
                killPlugin = p.getBoolean(PrefKeys.KILL_PLUGIN, false),
                blockDangerousMacros = p.getBoolean(PrefKeys.BLOCK_MACROS, true),
                swallowLatexFatal = p.getBoolean(PrefKeys.SWALLOW_FATAL, true),
                showToast = p.getBoolean(PrefKeys.TOAST, true),
                pushNotification = p.getBoolean(PrefKeys.NOTIFY, true),
                maxFormulasPerWindow = p.getInt(PrefKeys.MAX_FORMULAS, 24),
                hardFormulaLimit = p.getInt(PrefKeys.HARD_FORMULAS, 60),
                maxFormulaChars = p.getInt(PrefKeys.MAX_CHARS, 1200),
                maxNestingDepth = p.getInt(PrefKeys.MAX_DEPTH, 12),
                maxRuleEm = p.getInt(PrefKeys.MAX_RULE, 40),
                concurrentParse = p.getInt(PrefKeys.CONCURRENCY, 2),
                tripMillis = p.getInt(PrefKeys.TRIP_MS, 10_000),
                softBudgetCost = p.getInt(PrefKeys.SOFT_COST, 14_000)
            )
        } catch (_: Throwable) {
            GuardConfig()
        }
        if (force) {
            GuardRebuildHooks.apply(value)
        }
    }

    companion object {
        val INSTANCE = Config()

        /** 模块 App 侧写配置用（同进程，普通 prefs）。 */
        fun edit(context: Context): SharedPreferences =
            context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE)
    }
}

/** 配置变更时需要跟着变的东西（目前只有并发闸的许可数）。 */
object GuardRebuildHooks {
    fun apply(cfg: GuardConfig) {
        try {
            Guard.rebuildSemaphore(cfg.concurrentParse)
        } catch (_: Throwable) {
            // 宿主还没起 Guard，忽略
        }
    }
}

/**
 * 防护参数。所有默认值都偏保守——宁可少渲染一个公式，也不要让宿主卡死。
 */
data class GuardConfig(
    /** 总开关 */
    val masterEnabled: Boolean = true,
    /** LaTeX 防护总开关（关掉后本模块对公式不做任何事） */
    val latexGuardEnabled: Boolean = true,
    /** 踹飞模式：直接让 JLatexMathPlugin.configure 变成空实现，公式只显示源码 */
    val killPlugin: Boolean = false,
    /** 拦截 \def \newcommand \input 等可构造递归炸弹的宏 */
    val blockDangerousMacros: Boolean = true,
    /** 吞掉 latex 相关的 StackOverflowError，避免线程池把整个进程带走 */
    val swallowLatexFatal: Boolean = true,
    /** 熔断时弹 Toast */
    val showToast: Boolean = true,
    /** 熔断时推通知（由模块进程发出） */
    val pushNotification: Boolean = true,
    /** 1 秒窗口内允许渲染的公式数 */
    val maxFormulasPerWindow: Int = 24,
    /** 单条消息的公式数硬上限，超过直接不渲染 */
    val hardFormulaLimit: Int = 60,
    /** 单个公式源码字符上限 */
    val maxFormulaChars: Int = 1200,
    /** 花括号 / 上标嵌套深度上限 */
    val maxNestingDepth: Int = 12,
    /** \rule / \hspace 之类尺寸炸弹的 em 上限 */
    val maxRuleEm: Int = 40,
    /** 同时解析的公式数上限 */
    val concurrentParse: Int = 2,
    /** 熔断持续时间 */
    val tripMillis: Int = 10_000,
    /** 软预算：1 秒窗口内累计成本上限 */
    val softBudgetCost: Int = 14_000
)
