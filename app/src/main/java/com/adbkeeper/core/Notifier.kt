package com.adbkeeper.core

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 通知：三项设置全部正常且端口可用后，把 adb connect 命令推送到所选渠道。
 * 支持企业微信 / 钉钉 / 飞书 / Bark，失败自动重试，同一组地址+端口只发一次。
 * 所有耗时操作都在后台线程完成。
 */
object Notifier {

    private const val TAG = "Notify"
    private const val TITLE = "无线调试已就绪"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun channelLabel(context: Context): String = Channel.fromId(Prefs.channel(context)).label

    fun maybeNotify(context: Context) {
        val app = context.applicationContext
        executor.execute {
            if (!Prefs.notifyEnabled(app)) return@execute
            val hook = Prefs.webhook(app).trim()
            if (hook.isEmpty()) {
                Logs.i(TAG, "跳过：Webhook 未填写")
                return@execute
            }
            val snapshot = Keeper.snapshot(app)
            if (!snapshot.allOk) {
                Logs.i(TAG, "跳过：三项设置未全部正常")
                return@execute
            }
            val port = customPort(app) ?: AdbPort.read(app)
            if (port == null) {
                Logs.i(TAG, "跳过：读不到无线调试端口（可填自定义端口）")
                return@execute
            }
            val ipv4 = NetInfo.wifiIpv4(app)
            val ipv6 = NetInfo.wifiIpv6(app)
            if (ipv4 == null && ipv6 == null) {
                Logs.i(TAG, "跳过：没有 Wi-Fi 地址")
                return@execute
            }
            val signature = "$ipv4|$ipv6|$port"
            if (signature == Prefs.lastNotifySignature(app)) return@execute
            val content = buildMessage(app, ipv4, ipv6, port)
            Logs.i(TAG, "发送无线调试通知：$signature")
            val (ok, detail) = sendWithRetry(app, hook, content)
            if (ok) {
                Prefs.setLastNotifySignature(app, signature)
                Logs.i(TAG, "通知发送成功")
            } else {
                Logs.e(TAG, "通知发送失败：$detail")
            }
        }
    }

    fun sendTest(context: Context, callback: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        val hook = Prefs.webhook(app).trim()
        if (hook.isEmpty()) {
            callback(false, "请先填写机器人 Webhook")
            return
        }
        executor.execute {
            val port = customPort(app) ?: AdbPort.read(app)
            val content = buildMessage(app, NetInfo.wifiIpv4(app), NetInfo.wifiIpv6(app), port)
            val (ok, detail) = sendWithRetry(app, hook, content)
            if (ok) Prefs.setLastNotifySignature(app, "test|${System.currentTimeMillis()}")
            mainHandler.post { callback(ok, if (ok) "测试消息已发送" else detail) }
        }
    }

    /** 填了自定义端口就不再触发端口探测，省掉 getprop / mDNS。 */
    private fun customPort(context: Context): String? =
        Prefs.customPort(context).trim().toIntOrNull()?.takeIf { it in 1..65535 }?.toString()

    private fun buildMessage(context: Context, ipv4: String?, ipv6: String?, port: String?): String =
        buildString {
            append(TITLE).append('\n')
            append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("（Android ").append(Build.VERSION.RELEASE).append("）\n")
            if (port == null) {
                append("端口：暂未取到\n")
            } else {
                val v4 = AdbLink.ipv4(ipv4, port)
                val v6 = AdbLink.ipv6(ipv6, port)
                if (v4 != null) append("IPv4：").append(v4).append('\n')
                if (v6 != null) append("IPv6：").append(v6).append('\n')
                if (v4 == null && v6 == null) append("局域网地址：暂不可用（未连接 Wi-Fi）\n")
                append("端口：").append(port)
                    .append(if (AdbLink.isCustom(context)) "（自定义，本机中继）" else "（系统分配）")
                    .append('\n')
            }
            append("时间：").append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
        }

    /** 网络类错误与 5xx 重试两次，4xx 与业务错误直接失败。 */
    private fun sendWithRetry(context: Context, hook: String, content: String): Pair<Boolean, String> {
        val channel = Channel.fromId(Prefs.channel(context))
        var last = ""
        repeat(3) { attempt ->
            val result = sendOnce(channel, hook, content)
            if (result.ok) return true to "ok"
            last = result.detail
            if (!result.retryable) return false to last
            if (attempt < 2) {
                Logs.i(TAG, "第 ${attempt + 1} 次发送失败，稍后重试：$last")
                runCatching { Thread.sleep(if (attempt == 0) 1000L else 3000L) }
            }
        }
        return false to last
    }

    private data class Result(val ok: Boolean, val retryable: Boolean, val detail: String)

    private fun sendOnce(channel: Channel, hook: String, content: String): Result {
        return try {
            if (!hook.startsWith("http://") && !hook.startsWith("https://")) {
                return Result(false, false, "Webhook 需要以 http(s):// 开头")
            }
            val connection = (URL(hook).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(channel.payload(TITLE, content).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            connection.disconnect()
            when {
                code !in 200..299 -> Result(false, code >= 500, "HTTP $code ${text.take(120)}")
                channel.isBusinessError(text) -> Result(false, false, text.take(160))
                else -> Result(true, false, "ok")
            }
        } catch (t: Throwable) {
            Result(false, true, t.message ?: "网络异常")
        }
    }
}
