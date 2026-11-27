package com.adbkeeper.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.adbkeeper.core.AdbPort
import com.adbkeeper.core.EnforceCoordinator
import com.adbkeeper.core.Keeper
import com.adbkeeper.core.Logs
import com.adbkeeper.core.NetworkWatcher
import com.adbkeeper.core.Notifications
import com.adbkeeper.core.Notifier
import com.adbkeeper.core.PortRelay
import com.adbkeeper.core.Prefs
import com.adbkeeper.core.Session
import com.adbkeeper.core.SettingKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 常驻模式：前台服务 + 常驻通知，靠 ContentObserver、60 秒自检和网络变化回调维持三项设置。
 * 与 SessionService 互斥，由 ModeController 串行切换；不允许两个服务同时写 Global 设置。
 */
class PersistentKeeperService : Service() {

    companion object {
        private const val TAG = "Persistent"
        private const val TICK_MS = 60_000L
        const val ACTION_STOP = "com.adbkeeper.action.STOP_PERSISTENT"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var destroyed: CountDownLatch? = null

        fun start(context: Context) {
            destroyed = CountDownLatch(1)
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PersistentKeeperService::class.java),
                )
            }.onFailure { Logs.e(TAG, "启动常驻服务失败", it) }
        }

        /** 停止并等待 onDestroy 完成，供模式切换做"先停后起"。 */
        fun stopAndAwait(context: Context, timeoutMs: Long = 2000): Boolean {
            val latch = destroyed
            runCatching {
                context.startService(
                    Intent(context, PersistentKeeperService::class.java).setAction(ACTION_STOP),
                )
            }
            return latch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private val networkListener: () -> Unit = {
        Logs.i(TAG, "网络变化，重新校验")
        enforceNow("network")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val foreground = Notifications.persistent(this, Keeper.snapshot(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_PERSISTENT, foreground,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ID_PERSISTENT, foreground)
        }
        running = true
        registerObserver()
        NetworkWatcher.addListener(networkListener)
        Logs.i(TAG, "常驻服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logs.i(TAG, "收到停止指令")
            stopSelf()
            return START_NOT_STICKY
        }
        enforceNow("start")
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            enforceNow("tick")
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val debounced = Runnable { enforceNow("observer") }

    private fun enforceNow(source: String) {
        if (!Keeper.hasWritePermission(this)) {
            Logs.i(TAG, "缺少写设置权限，跳过（$source）")
            Notifications.updatePersistent(this, Keeper.snapshot(this))
            return
        }
        EnforceCoordinator.enforce(this, "persistent:$source")
        val snapshot = Keeper.snapshot(this)
        Notifications.updatePersistent(this, snapshot)
        val custom = Prefs.customPort(this).trim().toIntOrNull()?.takeIf { it in 1..65535 }
        PortRelay.sync(this, if (snapshot.allOk) custom else null)
        if (snapshot.allOk) Notifier.maybeNotify(this)
    }

    private fun registerObserver() {
        if (observer != null) return
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                AdbPort.invalidate()
                handler.removeCallbacks(debounced)
                handler.postDelayed(debounced, 1200)
            }
        }
        observer = o
        SettingKeys.all.forEach { key ->
            runCatching {
                contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, o)
            }
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        observer = null
        NetworkWatcher.removeListener(networkListener)
        PortRelay.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        destroyed?.countDown()
        Logs.i(TAG, "常驻服务已停止")
        super.onDestroy()
    }
}
