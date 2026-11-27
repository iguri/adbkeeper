package com.adbkeeper.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.adbkeeper.MainActivity
import com.adbkeeper.R
import com.adbkeeper.core.Keeper
import com.adbkeeper.core.ModeController

/**
 * 1×1 小组件：显示当前状态，点击切换开启/结束。
 * 用 PendingIntent.getActivity 而不是直接在广播里起服务，避免后台启动限制。
 */
class KeeperWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.adbkeeper.action.WIDGET_TOGGLE"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val snapshot = Keeper.snapshot(context)
        val active = ModeController.active(context)
        val label = when {
            !snapshot.hasPermission -> "未授权"
            active && snapshot.allOk -> "运行中"
            active -> "恢复中"
            else -> "已关闭"
        }
        return RemoteViews(context.packageName, R.layout.widget_keeper).apply {
            setTextViewText(R.id.widget_title, context.getString(R.string.widget_title))
            setTextViewText(R.id.widget_state, label)
            setOnClickPendingIntent(R.id.widget_root, togglePendingIntent(context))
        }
    }

    private fun togglePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
