package com.dafeiyu.paiguard

import com.dafeiyu.paiguard.core.Targets
import com.dafeiyu.paiguard.hooks.HookInstaller
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。LSPosed 会按 xposed_init 里写的类名反射实例化。
 *
 * 这里只做三件事：判断是不是目标、判断是不是 UI 进程、把 hook 挂上去。
 * 任何异常都吞掉并写日志——模块自己不能成为宿主崩溃的原因。
 */
class GuardEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (!Targets.matches(lpparam.packageName)) return
            if (!isUiProcess(lpparam)) {
                XposedBridge.log("[PaiGuard] 跳过非 UI 进程 ${lpparam.processName}")
                return
            }
            HookInstaller.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("[PaiGuard] 装载失败: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun isUiProcess(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val proc = lpparam.processName ?: return true
        if (proc == lpparam.packageName) return true
        // 元宝派的聊天界面偶尔在独立进程里，名字里带 chat
        return proc.contains("chat", ignoreCase = true) && !proc.contains("push", ignoreCase = true)
    }
}
