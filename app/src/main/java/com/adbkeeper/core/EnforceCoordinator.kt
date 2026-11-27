package com.adbkeeper.core

import android.content.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 所有写设置的入口（会话 tick、常驻 tick、ContentObserver、网络回调、磁贴）都经过这里，
 * 保证同一时刻只有一次 enforce，避免两个模式交叉写入。
 */
object EnforceCoordinator {

    private val lock = ReentrantLock()

    fun enforce(context: Context, source: String): EnforceResult = lock.withLock {
        val result = Keeper.enforce(context)
        if (result.verifyFailures.isNotEmpty()) {
            Logs.i("Enforce", "[$source] 未生效：${result.verifyFailures.joinToString("、")}")
        }
        result
    }

    fun <T> runExclusive(block: () -> T): T = lock.withLock(block)
}
