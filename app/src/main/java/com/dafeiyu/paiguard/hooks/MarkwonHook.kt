package com.dafeiyu.paiguard.hooks

import com.dafeiyu.paiguard.core.Config
import com.dafeiyu.paiguard.core.Guard
import com.dafeiyu.paiguard.core.Reporter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 第二道闸：Markdown 文本进渲染管线的那一刻。
 *
 * 对应需求里的「打开群聊的一瞬间自动检测」——RecyclerView 绑定消息时会调用
 * setMarkdown / toMarkdown / render，这里在文本进解析器之前数一遍公式。
 */
internal object MarkwonHook {

    private const val MARKWON = "io.noties.markwon.Markwon"

    private val ENTRY_METHODS = arrayOf("setMarkdown", "toMarkdown", "render")

    fun install(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(MARKWON, cl)
        if (cls == null) {
            Reporter.log("未找到 $MARKWON")
            return
        }

        var hooked = 0
        for (name in ENTRY_METHODS) {
            try {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cfg = Config.INSTANCE.current()
                        if (!cfg.masterEnabled || !cfg.latexGuardEnabled) return
                        val idx = indexOfText(param.args)
                        if (idx < 0) return
                        val raw = param.args[idx] as? CharSequence ?: return
                        if (raw.indexOf('$') < 0) return
                        val guarded = Guard.inspectMarkdown(raw)
                        if (guarded !== raw) {
                            param.args[idx] = guarded
                        }
                    }
                })
                hooked++
            } catch (t: Throwable) {
                Reporter.log("挂载 Markwon.$name 失败: ${t.message}")
            }
        }
        Reporter.log("Markwon 入口已挂载 $hooked 个方法")
    }

    /** 找到参数里那一个"要渲染的文本"。 */
    private fun indexOfText(args: Array<Any?>): Int {
        for (i in args.indices) {
            when (args[i]) {
                is CharSequence -> return i
                else -> Unit
            }
        }
        return -1
    }
}
