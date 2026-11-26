package com.adbkeeper.core

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志：同时写 logcat（tag=AdbKeeper）和应用私有文件，
 * 状态页可以直接查看/复制，ad 侧也可用 adb logcat -s AdbKeeper:V 实时观察。
 */
object Logs {

    private const val TAG = "AdbKeeper"
    private const val FILE_NAME = "keeper.log"
    private const val MAX_LINES_FULL = 800
    private const val MAX_LINES_LITE = 200
    private const val MAX_BYTES = 256 * 1024

    private val buffer = ArrayDeque<String>()
    private var logFile: File? = null
    private var fullMode = true
    private var initialized = false
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // 开发版批量落盘：先在内存里攒，1 秒或超过阈值再写一次文件
    private val pending = StringBuilder()
    private val flushHandler = Handler(Looper.getMainLooper())
    private var flushPosted = false

    /** 稳定版只保留内存日志，不写文件；开发版写文件并输出完整 logcat。 */
    fun init(context: Context, full: Boolean) {
        if (initialized) return
        initialized = true
        fullMode = full
        if (!full) {
            i(TAG, "日志启动：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
            return
        }
        val file = File(context.filesDir, FILE_NAME)
        logFile = file
        runCatching {
            if (file.exists()) {
                file.readLines().takeLast(MAX_LINES_FULL).forEach { buffer.addLast(it) }
            }
        }
        i(TAG, "日志启动：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
    }

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        write("E", tag, message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable.javaClass.simpleName)
                append(": ")
                append(throwable.message)
            }
        }
        synchronized(this) {
            buffer.addLast(line)
            val maxLines = if (fullMode) MAX_LINES_FULL else MAX_LINES_LITE
            while (buffer.size > maxLines) buffer.removeFirst()
            if (fullMode) {
                pending.append(line).append('\n')
                if (pending.length > 8000 || level == "E") {
                    flushPending()
                } else if (!flushPosted) {
                    flushPosted = true
                    flushHandler.postDelayed({
                        synchronized(this) {
                            flushPosted = false
                            flushPending()
                        }
                    }, 1000)
                }
            }
            runCatching {
                if (level == "E" || fullMode) {
                    if (level == "E") {
                        Log.e(TAG, "$tag: $message", throwable)
                    } else {
                        Log.i(TAG, "$tag: $message")
                    }
                }
            }
        }
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val text = pending.toString()
        pending.clear()
        runCatching {
            val file = logFile ?: return@runCatching
            if (file.length() > MAX_BYTES) {
                file.writeText(buffer.joinToString("\n") + "\n")
            } else {
                file.appendText(text)
            }
        }
    }

    fun dump(): String = synchronized(this) {
        flushPending()
        buffer.joinToString("\n")
    }

    fun tail(lines: Int): String = synchronized(this) { buffer.takeLast(lines).joinToString("\n") }

    fun clear() {
        synchronized(this) {
            buffer.clear()
            pending.clear()
            runCatching { logFile?.writeText("") }
        }
    }

    fun isFullMode(): Boolean = fullMode
}
