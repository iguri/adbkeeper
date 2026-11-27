package com.adbkeeper.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 更新通道：读取 GitHub Releases，只做检查并跳到浏览器，不在应用内下载 APK。 */
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
                parseRelease(text)
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

    private fun parseRelease(text: String): UpdateInfo {
        val json = JSONObject(text)
        val version = json.optString("tag_name").removePrefix("v").ifBlank { json.optString("name") }
        val page = json.optString("html_url").ifBlank { "$REPO_URL/releases" }
        return UpdateInfo(version, page, json.optString("body"))
    }

    /** 用浏览器打开 Release 页，由用户自行下载安装。 */
    fun openReleasePage(context: Context, info: UpdateInfo): Boolean = runCatching {
        val url = info.url.ifBlank { REPO_URL }
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse { false }

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
}
