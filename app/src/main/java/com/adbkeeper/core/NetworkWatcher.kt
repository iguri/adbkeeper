package com.adbkeeper.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 监听默认网络变化：Wi-Fi 切换、IPv6 地址到来/消失等。
 * 变化时作废旧端口缓存，刷新界面，并在会话内补发一次连接信息通知。
 */
object NetworkWatcher {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var registered = false
    private var lastEventAt = 0L

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun start(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed(app, "网络可用")
            override fun onLost(network: Network) = changed(app, "网络断开")
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) =
                changed(app, "网络能力变化")
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        }
    }

    private fun changed(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastEventAt < 5000) return // 系统可能连续回调，合并处理
        lastEventAt = now
        Logs.i("NetWatch", "$reason，刷新地址与端口")
        AdbPort.invalidate()
        mainHandler.post { listeners.forEach { runCatching { it() } } }
        if (Session.active) Notifier.maybeNotify(context)
    }
}
