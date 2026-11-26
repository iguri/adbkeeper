package com.adbkeeper.core

import android.content.Context

/**
 * 连接地址与端口。
 *
 * 无线调试端口由 adbd 每次开启时随机分配（service.adb.tls.port），普通应用无法指定；
 * 这里允许在设置里填「自定义端口」：仅影响本应用展示和通知里的 adb connect 命令，
 * 适合前面有端口映射 / 反向代理的场景。留空时使用系统实际端口。
 */
object AdbLink {

    fun effectivePort(context: Context, detected: String?): String? {
        val custom = Prefs.customPort(context).trim()
        if (custom.isNotEmpty() && (custom.toIntOrNull() ?: 0) in 1..65535) return custom
        return detected?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun isCustom(context: Context): Boolean {
        val custom = Prefs.customPort(context).trim()
        return custom.isNotEmpty() && (custom.toIntOrNull() ?: 0) in 1..65535
    }

    fun ipv4(ip: String?, port: String?): String? =
        if (!ip.isNullOrBlank() && !port.isNullOrBlank()) "adb connect $ip:$port" else null

    fun ipv6(ip: String?, port: String?): String? =
        if (!ip.isNullOrBlank() && !port.isNullOrBlank()) "adb connect [$ip]:$port" else null
}
