package com.adbkeeper.core

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 更新通道：固定读取 GitHub Releases，可下载并调起安装。 */
object Updater {

    const val REPO = "iguri/adbkeeper"
    const val REPO_URL = "https://github.com/$REPO"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(val versionName: String, val url: String, val notes: String)

    /** 回调：(是否有新版本, 提示信息, 更新详情)。 */
    fun check(currentVersion: String, flavor: String, callback: (Boolean, String, UpdateInfo?) -> Unit) {
        Thread {
            val result = runCatching {
                val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "AdbKeeper")
                }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (code !in 200..299) error("HTTP $code")
                parseRelease(text, flavor)
            }
            result.fold(
                onSuccess = { info ->
                    val newer = isNewer(info.versionName, currentVersion)
                    callback(newer, if (newer) "发现新版本 ${info.versionName}" else "已是最新", if (newer) info else null)
                },
                onFailure = { callback(false, it.message ?: "检查失败", null) },
            )
        }.start()
    }

    private fun parseRelease(text: String, flavor: String): UpdateInfo {
        val json = JSONObject(text)
        val version = json.optString("tag_name").removePrefix("v").ifBlank { json.optString("name") }
        val notes = json.optString("body")
        val assets = json.optJSONArray("assets")
        var fallback = ""
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!name.endsWith(".apk")) continue
                if (name.contains(flavor, ignoreCase = true)) {
                    return UpdateInfo(version, url, notes)
                }
                if (fallback.isEmpty()) fallback = url
            }
        }
        return UpdateInfo(version, fallback, notes)
    }

    /** 纯函数：比较 2.1 / 2.1.1 / 2.2-dev 这类版本号。 */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(value: String): List<Int> = value
            .substringBefore('-')
            .removePrefix("v")
            .split('.')
            .map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(current)
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 下载并调起安装；返回给界面展示的提示。 */
    fun downloadAndInstall(context: Context, info: UpdateInfo): String {
        if (info.url.isBlank()) return "该 Release 没有可下载的 APK"
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return "请先允许本应用安装未知来源应用，然后再点下载"
        }
        val manager = context.getSystemService(DownloadManager::class.java) ?: return "系统下载服务不可用"
        val fileName = "adbkeeper-${info.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(info.url))
            .setTitle("无线调试助手 ${info.versionName}")
            .setDescription("正在下载更新包")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = runCatching { manager.enqueue(request) }.getOrElse { return "下载失败：${it.message}" }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                runCatching { ctx.unregisterReceiver(this) }
                val uri = manager.getUriForDownloadedFile(id)
                if (uri == null) {
                    runCatching {
                        ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    return
                }
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return "已开始下载，完成后会弹出安装界面"
    }
}
