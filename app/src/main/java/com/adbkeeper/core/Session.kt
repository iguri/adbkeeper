package com.adbkeeper.core

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.adbkeeper.service.SessionService

/**
 * 会话制：只在用户点「一键开启」后临时打开无线调试。
 *
 * - 开始时记录三项设置的原始值；
 * - 会话期间由 ContentObserver + 定时兜底保持（稳定 3 分钟后自检从 20 秒退到 60 秒）；
 * - 返回键退出或划掉后台卡片时还原原始值；
 * - 进程被杀时下次启动先还原；超过设定的会话时长也会自动还原，避免长期挂着无线调试。
 * 不常驻：没有前台服务、没有 WorkManager、没有开机广播。
 */
object Session {

    private const val TAG = "Session"
    private const val TICK_FAST_MS = 20_000L
    private const val TICK_SLOW_MS = 60_000L
    private const val STABLE_TICKS = 9 // 9 * 20s = 3 分钟无修复后降频

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var observer: ContentObserver? = null
    private var stableTicks = 0

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var originals: Map<String, String?> = emptyMap()
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        active = Prefs.sessionActive(context)
        originals = Prefs.loadOriginals(context)
    }

    /** 上次进程被杀没来得及还原，启动时补一次。 */
    fun recoverIfLeaked(context: Context) {
        if (!active) return
        Logs.i(TAG, "发现上次会话未正常结束，先还原：$originals")
        restore(context)
        clear(context)
    }

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (active) {
            enforceNow(app)
            return
        }
        val captured = SettingKeys.all.associateWith { key ->
            Keeper.rawValue(app.contentResolver, key)
        }
        originals = captured
        Prefs.saveOriginals(app, captured)
        Prefs.setSessionActive(app, true)
        Prefs.setSessionStartedAt(app, System.currentTimeMillis())
        active = true
        stableTicks = 0
        Logs.i(TAG, "开始会话，原始值：$captured")
        startWatch(app)
        runCatching { app.startService(Intent(app, SessionService::class.java)) }
        enforceNow(app)
    }

    fun stop(context: Context, restore: Boolean = true) {
        val app = context.applicationContext
        if (!active && originals.isEmpty()) return
        Logs.i(TAG, "结束会话，还原=${restore}")
        if (restore) {
            if (!this.restore(app)) {
                Logs.i(TAG, "无写设置权限，未能自动还原（原值：$originals）")
            }
        }
        clear(app)
    }

    fun restore(context: Context): Boolean {
        if (originals.isEmpty()) return true
        if (!Keeper.hasWritePermission(context)) return false
        val ok = Keeper.restore(context, originals)
        Logs.i(TAG, if (ok) "已还原：$originals" else "还原失败")
        return ok
    }

    /** 常驻模式复用同一份原始值存储：启用前记录，关闭时还原。 */
    fun captureOriginals(context: Context): Map<String, String?> {
        val app = context.applicationContext
        val captured = SettingKeys.all.associateWith { key ->
            Keeper.rawValue(app.contentResolver, key)
        }
        originals = captured
        Prefs.saveOriginals(app, captured)
        Logs.i(TAG, "记录原始值（常驻模式）：$captured")
        return captured
    }

    /** 常驻模式关闭：还原并清空记录，不触碰会话服务。 */
    fun restoreAndClear(context: Context): Boolean {
        val app = context.applicationContext
        val ok = restore(app)
        originals = emptyMap()
        Prefs.clearOriginals(app)
        return ok
    }

    /** 距会话结束的剩余秒数，未限时返回 null。 */
    fun remainingSeconds(context: Context): Long? {
        if (!active) return null
        val limit = Prefs.sessionLimitMinutes(context)
        if (limit <= 0) return null
        val started = Prefs.sessionStartedAt(context)
        val remain = limit * 60L - (System.currentTimeMillis() - started) / 1000L
        return remain.coerceAtLeast(0)
    }

    private fun clear(app: Context) {
        stopWatch()
        PortRelay.stop()
        active = false
        originals = emptyMap()
        Prefs.setSessionActive(app, false)
        Prefs.clearOriginals(app)
        runCatching { app.stopService(Intent(app, SessionService::class.java)) }
    }

    private fun enforceNow(context: Context) {
        if (!active) return
        val result = EnforceCoordinator.enforce(context, "session")
        if (!result.hasPermission) {
            Logs.i(TAG, "缺少写设置权限，请先执行界面上的授权命令")
        }
        if (result.fixed.isNotEmpty()) {
            Logs.i(TAG, "会话内修复：${result.fixed.joinToString("、")}")
        }
        val snapshot = Keeper.snapshot(context)
        stableTicks = if (snapshot.allOk && result.fixed.isEmpty()) stableTicks + 1 else 0
        // 自定义端口通过本机中继实现，只在会话内且三项正常时监听
        val custom = Prefs.customPort(context).trim().toIntOrNull()?.takeIf { it in 1..65535 }
        PortRelay.sync(context, if (snapshot.allOk) custom else null)
        if (snapshot.allOk) Notifier.maybeNotify(context)
    }

    private fun startWatch(context: Context) {
        if (observer != null) return
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // 无线调试开关变化意味着端口可能换了，作废旧端口
                AdbPort.invalidate()
                handler.removeCallbacks(enforceOnce)
                handler.postDelayed(enforceOnce, 1200)
            }
        }
        observer = contentObserver
        SettingKeys.all.forEach { key ->
            runCatching {
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(key),
                    false,
                    contentObserver,
                )
            }
        }
        handler.postDelayed(tick, TICK_FAST_MS)
    }

    private fun stopWatch() {
        handler.removeCallbacksAndMessages(null)
        observer?.let { contentObserver ->
            runCatching { appContext?.contentResolver?.unregisterContentObserver(contentObserver) }
        }
        observer = null
        stableTicks = 0
    }

    private val enforceOnce = Runnable { appContext?.let { enforceNow(it) } }

    private val tick = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            val limit = Prefs.sessionLimitMinutes(context)
            val started = Prefs.sessionStartedAt(context)
            if (limit > 0 && started > 0 &&
                System.currentTimeMillis() - started > limit * 60_000L
            ) {
                Logs.i(TAG, "会话超过 $limit 分钟，自动结束并还原")
                stop(context, restore = true)
                return
            }
            enforceNow(context)
            if (active) {
                handler.postDelayed(this, if (stableTicks >= STABLE_TICKS) TICK_SLOW_MS else TICK_FAST_MS)
            }
        }
    }
}
