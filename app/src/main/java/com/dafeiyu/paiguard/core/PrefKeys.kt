package com.dafeiyu.paiguard.core

/**
 * 配置键名。故意跟 [Config] 分开放：
 * 模块界面（非 Xposed 进程）也要读写这些键，不能顺带把 Xposed 的类加载进来。
 */
object PrefKeys {
    const val FILE = "paiguard"

    const val MASTER = "master_enabled"
    const val LATEX_GUARD = "latex_guard_enabled"
    const val KILL_PLUGIN = "kill_plugin"
    const val BLOCK_MACROS = "block_dangerous_macros"
    const val SWALLOW_FATAL = "swallow_latex_fatal"
    const val TOAST = "show_toast"
    const val NOTIFY = "push_notification"
    const val MAX_FORMULAS = "max_formulas_per_window"
    const val HARD_FORMULAS = "hard_formula_limit"
    const val MAX_CHARS = "max_formula_chars"
    const val MAX_DEPTH = "max_nesting_depth"
    const val MAX_RULE = "max_rule_em"
    const val CONCURRENCY = "concurrent_parse"
    const val TRIP_MS = "trip_millis"
    const val SOFT_COST = "soft_budget_cost"
}
