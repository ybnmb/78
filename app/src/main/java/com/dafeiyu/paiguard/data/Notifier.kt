package com.dafeiyu.paiguard.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dafeiyu.paiguard.R
import com.dafeiyu.paiguard.ui.MainActivity

/**
 * 熔断提示的通知通道。
 * 「自动发出万科」这件事在目标进程里就是一条 Toast + 这一条通知。
 */
object Notifier {

    private const val CHANNEL_ID = "guard_events"
    private const val NOTIFY_ID = 1001

    fun warn(context: Context, title: String, detail: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notify_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notify_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getActivity(context, 0, intent, flags)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            builder.setSmallIcon(R.drawable.ic_stat_guard)
                .setContentTitle(title)
                .setContentText(detail.lineSequence().firstOrNull() ?: "")
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setAutoCancel(true)
                .setContentIntent(pending)

            manager.notify(NOTIFY_ID, builder.build())
        } catch (t: Throwable) {
            // 通知权限被拒 / 渠道被删：静默失败，事件仍然记在库里
        }
    }
}
