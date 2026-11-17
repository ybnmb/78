package com.dafeiyu.paiguard.core

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 宿主 App 的上下文与线程工具。
 *
 * 所有对外调用都包了 try/catch —— 防护模块自己崩掉比不防护更糟。
 */
internal object Host {

    @Volatile
    private var app: Application? = null

    private val main = Handler(Looper.getMainLooper())

    fun application(): Application? {
        app?.let { return it }
        val ctx = try {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
            ) as? Application
        } catch (_: Throwable) {
            null
        }
        app = ctx
        return ctx
    }

    fun context(): Context? = application()

    fun toast(text: String) {
        val ctx = context() ?: return
        main.post {
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                XposedBridge.log("[PaiGuard] toast 失败: ${t.message}")
            }
        }
    }

    fun now(): Long = SystemClock.elapsedRealtime()

    /** 进程名，用于只在 UI 进程里装 hook。 */
    fun processName(): String? = try {
        XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentProcessName"
        ) as? String
    } catch (_: Throwable) {
        null
    }
}
