package com.adbkeeper.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adbkeeper.MainActivity
import com.adbkeeper.R

/** 常驻模式通知与辅助通知的统一构建。 */
object Notifications {

    const val CHANNEL_KEEP = "keeper_persistent"
    const val CHANNEL_ASSIST = "keeper_assist"
    const val ID_PERSISTENT = 1001
    const val ID_ASSIST = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEP, "常驻维持", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻模式下维持无线调试状态"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ASSIST, "配对与授权提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "USB 辅助与授权引导"
            },
        )
    }

    fun persistent(context: Context, snapshot: Snapshot): Notification {
        ensureChannels(context)
        val status = when {
            !snapshot.hasPermission -> "缺少写权限"
            snapshot.allOk -> "无线调试已就绪"
            else -> "有 ${snapshot.brokenCount} 项未生效"
        }
        return NotificationCompat.Builder(context, CHANNEL_KEEP)
            .setSmallIcon(R.drawable.ic_stat_keeper)
            .setContentTitle("无线调试助手 · 常驻")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
            .addAction(0, "停止", stopIntent(context))
            .build()
    }

    fun updatePersistent(context: Context, snapshot: Snapshot) {
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_PERSISTENT, persistent(context, snapshot))
        }
    }

    fun assist(context: Context, title: String, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ASSIST)
            .setSmallIcon(R.drawable.ic_stat_keeper)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_ASSIST, notification) }
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, com.adbkeeper.service.PersistentKeeperService::class.java)
            .setAction(com.adbkeeper.service.PersistentKeeperService.ACTION_STOP)
        return PendingIntent.getService(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
