package com.adbkeeper.core

import android.content.Context
import android.content.SharedPreferences

/** 应用配置与运行期缓存。 */
object Prefs {

    private const val FILE = "adbkeeper"
    private const val KEY_NOTIFY_ENABLED = "notify_enabled"
    private const val KEY_WEBHOOK = "webhook"
    private const val KEY_CHANNEL = "notify_channel"
    private const val KEY_CUSTOM_PORT = "custom_port"
    private const val KEY_LAST_SIG = "last_notify_sig"
    private const val KEY_SESSION_ACTIVE = "session_active"
    private const val KEY_SESSION_STARTED = "session_started_at"
    private const val KEY_SESSION_LIMIT = "session_limit_minutes"
    private const val KEY_ORIGINAL_PREFIX = "original_"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun notifyEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_NOTIFY_ENABLED, false)

    fun setNotifyEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_NOTIFY_ENABLED, value).apply()
    }

    fun webhook(context: Context): String = sp(context).getString(KEY_WEBHOOK, "").orEmpty()

    fun setWebhook(context: Context, value: String) {
        sp(context).edit().putString(KEY_WEBHOOK, value).apply()
    }

    fun channel(context: Context): String = sp(context).getString(KEY_CHANNEL, Channel.WECOM.id).orEmpty()

    fun setChannel(context: Context, value: String) {
        sp(context).edit().putString(KEY_CHANNEL, value).apply()
    }

    fun customPort(context: Context): String = sp(context).getString(KEY_CUSTOM_PORT, "").orEmpty()

    fun setCustomPort(context: Context, value: String) {
        sp(context).edit().putString(KEY_CUSTOM_PORT, value).apply()
    }

    fun lastNotifySignature(context: Context): String =
        sp(context).getString(KEY_LAST_SIG, "").orEmpty()

    fun setLastNotifySignature(context: Context, value: String) {
        sp(context).edit().putString(KEY_LAST_SIG, value).apply()
    }

    fun sessionActive(context: Context): Boolean = sp(context).getBoolean(KEY_SESSION_ACTIVE, false)

    fun setSessionActive(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SESSION_ACTIVE, value).apply()
    }

    fun sessionStartedAt(context: Context): Long = sp(context).getLong(KEY_SESSION_STARTED, 0L)

    fun setSessionStartedAt(context: Context, value: Long) {
        sp(context).edit().putLong(KEY_SESSION_STARTED, value).apply()
    }

    /** 会话最长时长（分钟），0 表示不限。默认 120 分钟，防止进程被杀后无线调试一直挂着。 */
    fun sessionLimitMinutes(context: Context): Int = sp(context).getInt(KEY_SESSION_LIMIT, 120)

    fun setSessionLimitMinutes(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_SESSION_LIMIT, value.coerceIn(0, 24 * 60)).apply()
    }

    /** 记录会话开始前的原始值（null 表示该项原本不存在）。 */
    fun saveOriginals(context: Context, originals: Map<String, String?>) {
        val editor = sp(context).edit()
        originals.forEach { (key, value) ->
            editor.putString(KEY_ORIGINAL_PREFIX + key, value)
            editor.putBoolean(KEY_ORIGINAL_PREFIX + key + "_exists", value != null)
        }
        editor.apply()
    }

    fun loadOriginals(context: Context): Map<String, String?> {
        val prefs = sp(context)
        val result = mutableMapOf<String, String?>()
        for (key in SettingKeys.all) {
            if (!prefs.contains(KEY_ORIGINAL_PREFIX + key + "_exists")) continue
            result[key] = prefs.getString(KEY_ORIGINAL_PREFIX + key, null)
        }
        return result
    }

    fun clearOriginals(context: Context) {
        val editor = sp(context).edit()
        SettingKeys.all.forEach { key ->
            editor.remove(KEY_ORIGINAL_PREFIX + key)
            editor.remove(KEY_ORIGINAL_PREFIX + key + "_exists")
        }
        editor.apply()
    }
}
