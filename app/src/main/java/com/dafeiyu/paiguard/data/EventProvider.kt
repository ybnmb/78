package com.dafeiyu.paiguard.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.dafeiyu.paiguard.core.PrefKeys

/**
 * 宿主进程 → 模块 App 的事件通道。
 *
 * 为什么不用文件：两个 App 是两个 UID，互相读不到对方的私有目录。
 * 为什么不用广播：Android 8 之后隐式广播基本注册不上。
 * ContentProvider.call 是最省事、也最稳的一条路。
 *
 * 它是 exported 的，但带了 normal 级自定义权限——只防君子，不防恶意应用
 * （恶意应用照样能声明这个权限）。所以这里只收"一条事件"，
 * 不提供任何读取能力，读接口在模块自己的进程里。
 */
class EventProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_LOG || extras == null) return null
        val ctx = context ?: return null

        val kind = extras.getString("kind") ?: Event.KIND_INFO
        val title = extras.getString("title") ?: ""
        val detail = extras.getString("detail") ?: ""
        val count = extras.getInt("count", 0)
        val time = extras.getLong("time", System.currentTimeMillis())

        try {
            EventStore.get(ctx).insert(kind, title, detail, count, time)
        } catch (_: Throwable) {
            return null
        }

        if (kind == Event.KIND_TRIP || kind == Event.KIND_FATAL) {
            maybeNotify(ctx, kind, title, detail)
        }

        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun maybeNotify(ctx: Context, kind: String, title: String, detail: String) {
        val prefs = ctx.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PrefKeys.MASTER, true)) return
        if (!prefs.getBoolean(PrefKeys.NOTIFY, true)) return
        if (kind == Event.KIND_TRIP && !prefs.getBoolean(PrefKeys.LATEX_GUARD, true)) return
        Notifier.warn(ctx, title.ifEmpty { "元宝派防护" }, detail)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.dafeiyu.paiguard.events"
        const val METHOD_LOG = "log"
        val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "event", 1)
        }
    }
}
