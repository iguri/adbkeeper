package com.adbkeeper.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.adbkeeper.core.Logs
import com.adbkeeper.core.Session

/**
 * 会话期间存在的轻量服务，不做常驻：没有前台通知、START_NOT_STICKY。
 * 唯一职责是拿到「任务被划掉」的回调，把设置还原成会话前的状态。
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logs.i("Session", "后台卡片被划掉，还原设置")
        Session.stop(this, restore = true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
