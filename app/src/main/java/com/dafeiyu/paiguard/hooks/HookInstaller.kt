package com.dafeiyu.paiguard.hooks

import com.dafeiyu.paiguard.core.Config
import com.dafeiyu.paiguard.core.PrefKeys
import com.dafeiyu.paiguard.core.Reporter
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 装配区：所有 hook 从这里一次性挂上去。
 */
internal object HookInstaller {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        Reporter.log("装载 ${lpparam.packageName} (process=${lpparam.processName})")

        Config.INSTANCE.attach(MODULE_PACKAGE, PrefKeys.FILE)
        Config.INSTANCE.current().let {
            Reporter.log(
                "配置: master=${it.masterEnabled} latex=${it.latexGuardEnabled} " +
                    "kill=${it.killPlugin} maxPerWindow=${it.maxFormulasPerWindow} " +
                    "hardLimit=${it.hardFormulaLimit} maxChars=${it.maxFormulaChars}"
            )
        }

        PluginHook.install(cl)
        MarkwonHook.install(cl)
        RenderHook.install(cl)
        CrashHook.install(cl)
    }

    /** 必须与 app/build.gradle.kts 里的 applicationId 一致。 */
    const val MODULE_PACKAGE = "com.dafeiyu.paiguard"
}
