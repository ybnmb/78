package com.dafeiyu.paiguard.hooks

import com.dafeiyu.paiguard.core.Config
import com.dafeiyu.paiguard.core.Guard
import com.dafeiyu.paiguard.core.Reporter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 第一道闸：直接针对 Markwon 的 LaTeX 插件。
 *
 * 两种模式：
 * - 常规模式：什么都不做，让 [com.dafeiyu.paiguard.hooks.RenderHook] 在解析层做细粒度控制。
 * - 踹飞模式（killPlugin）：把 `JLatexMathPlugin.configure` 变成空方法。
 *   插件不注册 span factory，Markwon 就把 `$$...$$` 当普通文本渲染——一行代码，彻底不渲染。
 */
internal object PluginHook {

    private const val PLUGIN = "io.noties.markwon.ext.latex.JLatexMathPlugin"

    fun install(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(PLUGIN, cl)
        if (cls == null) {
            Reporter.log("未找到 $PLUGIN，宿主可能未使用 Markwon ext-latex")
            return
        }

        try {
            XposedBridge.hookAllMethods(cls, "configure", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cfg = Config.INSTANCE.current()
                    if (!cfg.masterEnabled || !cfg.latexGuardEnabled) return
                    if (!cfg.killPlugin) return
                    // 返回 null 即"方法体不执行"，插件不会注册任何渲染器
                    param.result = null
                }
            })
            Reporter.log("已挂载 $PLUGIN.configure（踹飞开关就绪）")
        } catch (t: Throwable) {
            Reporter.log("挂载 $PLUGIN 失败: ${t.message}")
        }

        // 有些版本会缓存 plugin 实例并用 create(...) 构建，这里顺手记录一下，
        // 方便日志里确认宿主到底怎么用的（不改变行为）。
        try {
            XposedBridge.hookAllMethods(cls, "create", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!Guard.isInstalled()) {
                        Reporter.log("Markwon LaTeX 插件已创建，防护已生效")
                        Guard.markInstalled()
                    }
                }
            })
        } catch (_: Throwable) {
            // create 是静态工厂，某些版本没有，忽略
        }
    }
}
