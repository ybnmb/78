package com.dafeiyu.paiguard.core

import android.net.Uri
import android.os.Bundle
import de.robv.android.xposed.XposedBridge

/**
 * 事件上报。
 *
 * 宿主进程和模块 App 是两个沙箱，写不了同一个文件。走 ContentProvider 把事件
 * 送进模块 App 自己的库，用户在模块界面里就能看到「谁、什么时候、发了几个公式」。
 * 上报失败只写 Xposed 日志，绝不影响宿主。
 */
internal object Reporter {

    const val AUTHORITY = "com.dafeiyu.paiguard.events"
    private val URI: Uri = Uri.parse("content://$AUTHORITY/event")

    const val KIND_TRIP = "trip"
    const val KIND_BLOCK = "block"
    const val KIND_FATAL = "fatal"
    const val KIND_INFO = "info"

    /** 刷屏时会有成百上千条拦截，跨进程调用不能每条都发。同类事件 400ms 合并一次。 */
    private val lastSent = HashMap<String, Long>(4)
    private const val MIN_INTERVAL_MS = 400L

    fun send(kind: String, title: String, detail: String, count: Int = 0) {
        val ctx = Host.context() ?: return

        if (kind != KIND_FATAL) {
            val now = System.currentTimeMillis()
            synchronized(lastSent) {
                val last = lastSent[kind] ?: 0L
                if (now - last < MIN_INTERVAL_MS) return
                lastSent[kind] = now
            }
        }

        try {
            val extras = Bundle().apply {
                putString("kind", kind)
                putString("title", title.take(200))
                putString("detail", detail.take(4000))
                putInt("count", count)
                putLong("time", System.currentTimeMillis())
            }
            ctx.contentResolver.call(URI, "log", null, extras)
        } catch (t: Throwable) {
            // 模块被卸载 / provider 未启用 / 权限被改：降级为日志
            XposedBridge.log("[PaiGuard] 上报失败(${t.javaClass.simpleName}): $title")
        }
    }

    fun log(msg: String) {
        try {
            XposedBridge.log("[PaiGuard] $msg")
        } catch (_: Throwable) {
            // ignore
        }
    }
}
