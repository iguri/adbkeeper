package com.adbkeeper.core

import android.content.Context
import com.adbkeeper.service.PersistentKeeperService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 双模式的唯一入口。所有切换都在同一把锁里完成，顺序固定为
 * 「停旧（等待 onDestroy）→ 记录/恢复原始值 → 启新」，避免两个服务同时写 Global 设置。
 */
object ModeController {

    private const val TAG = "Mode"

    enum class Mode(val id: String, val label: String) {
        SESSION("session", "即开即用"),
        PERSISTENT("persistent", "常驻"),
        ;

        companion object {
            fun fromId(id: String): Mode = entries.firstOrNull { it.id == id } ?: SESSION
        }
    }

    private val lock = ReentrantLock()

    fun mode(context: Context): Mode = Mode.fromId(Prefs.mode(context))

    /** 会话或常驻任一处于维持状态。 */
    fun active(context: Context): Boolean = Session.active || PersistentKeeperService.running

    fun start(context: Context) = lock.withLock {
        val app = context.applicationContext
        when (mode(app)) {
            Mode.SESSION -> Session.start(app)
            Mode.PERSISTENT -> {
                if (PersistentKeeperService.running) return@withLock
                // 常驻等价于"无限期会话"，同样记录原始值以便关闭时还原
                Session.captureOriginals(app)
                AdbPort.invalidate()
                PersistentKeeperService.start(app)
            }
        }
        Logs.i(TAG, "已启动 ${mode(app).label}")
    }

    fun stop(context: Context, restore: Boolean = true) = lock.withLock {
        val app = context.applicationContext
        if (Session.active) {
            Session.stop(app, restore)
        }
        if (PersistentKeeperService.running) {
            val awaited = PersistentKeeperService.stopAndAwait(app)
            if (!awaited) Logs.i(TAG, "常驻服务未在 2s 内停止，继续后续清理")
            if (restore) Session.restoreAndClear(app)
        }
    }

    /** 切换模式：正在维持时先停后起，未维持时只改偏好。 */
    fun switchMode(context: Context, target: Mode) = lock.withLock {
        val app = context.applicationContext
        val wasActive = active(app)
        if (mode(app) == target) return@withLock
        if (wasActive) stop(app, restore = true)
        Prefs.setMode(app, target.id)
        Logs.i(TAG, "切换到 ${target.label}")
        if (wasActive) start(app)
    }
}
