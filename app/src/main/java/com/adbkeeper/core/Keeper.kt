package com.adbkeeper.core

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * 需要长期保持的三项系统设置。写入需要 WRITE_SECURE_SETTINGS，
 * 该权限带有 development 保护级别，可通过 adb 的 pm grant 一次性授予。
 */
object SettingKeys {
    /** 开发者选项总开关 */
    const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"

    /** 无线调试开关（Android 11+） */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** adb 授权有效期（毫秒），0 表示永不过期，系统默认 7 天 */
    const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    val all = listOf(DEVELOPMENT_SETTINGS_ENABLED, ADB_WIFI_ENABLED, ADB_ALLOWED_CONNECTION_TIME)
}

data class ItemStatus(
    val label: String,
    val key: String,
    val raw: String?,
    val ok: Boolean,
    /** 已写入但回读仍不符：该 ROM 拦截了这个 key。 */
    val writtenButIgnored: Boolean = false,
)

data class Snapshot(
    val hasPermission: Boolean,
    val items: List<ItemStatus>,
) {
    val allOk: Boolean get() = hasPermission && items.all { it.ok }
    val brokenCount: Int get() = if (hasPermission) items.count { !it.ok } else items.size
    val ignoredKeys: List<String> get() = items.filter { it.writtenButIgnored }.map { it.key }
}

data class EnforceResult(
    val hasPermission: Boolean,
    val fixed: List<String>,
    val error: String?,
    /** 写了但回读仍不符的 key，用于给出针对性提示。 */
    val verifyFailures: List<String> = emptyList(),
)

object Keeper {

    /** 最近一次 enforce 中"写入但回读未生效"的 key，供 UI 给出针对性提示。 */
    @Volatile
    private var lastIgnored: Set<String> = emptySet()

    fun hasWritePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** 读取原始值。数值型设置在部分 ROM 上可能以字符串形式存储，统一按字符串读取后再解析。 */
    fun rawValue(resolver: ContentResolver, key: String): String? = try {
        Settings.Global.getString(resolver, key)
    } catch (t: Throwable) {
        null
    }

    fun snapshot(context: Context): Snapshot {
        val resolver = context.contentResolver
        val ignored = lastIgnored
        val dev = rawValue(resolver, SettingKeys.DEVELOPMENT_SETTINGS_ENABLED)
        val wifi = rawValue(resolver, SettingKeys.ADB_WIFI_ENABLED)
        val timeout = rawValue(resolver, SettingKeys.ADB_ALLOWED_CONNECTION_TIME)
        return Snapshot(
            hasPermission = hasWritePermission(context),
            items = listOf(
                ItemStatus(
                    label = "开发者选项",
                    key = SettingKeys.DEVELOPMENT_SETTINGS_ENABLED,
                    raw = dev,
                    ok = dev?.trim()?.toIntOrNull() == 1,
                    writtenButIgnored = SettingKeys.DEVELOPMENT_SETTINGS_ENABLED in ignored,
                ),
                ItemStatus(
                    label = "无线调试",
                    key = SettingKeys.ADB_WIFI_ENABLED,
                    raw = wifi,
                    ok = wifi?.trim()?.toIntOrNull() == 1,
                    writtenButIgnored = SettingKeys.ADB_WIFI_ENABLED in ignored,
                ),
                ItemStatus(
                    label = "adb 授权不过期",
                    key = SettingKeys.ADB_ALLOWED_CONNECTION_TIME,
                    raw = timeout,
                    ok = timeout?.trim()?.toLongOrNull() == 0L,
                    writtenButIgnored = SettingKeys.ADB_ALLOWED_CONNECTION_TIME in ignored,
                ),
            ),
        )
    }

    /**
     * 恢复会话开始前的原始值。value 为 null 时删除该项，回到系统默认状态。
     */
    fun restore(context: Context, originals: Map<String, String?>): Boolean {
        if (!hasWritePermission(context)) return false
        return runCatching {
            originals.forEach { (key, value) ->
                Settings.Global.putString(context.contentResolver, key, value)
            }
            true
        }.onFailure { Logs.e("Keeper", "还原设置失败", it) }
            .getOrDefault(false)
    }

    /** 检查并把三项设置纠正为目标值。只写入确实不对的项，避免无意义的设置变更通知。
     * 顺序很重要：先保证开发者选项开启，无线调试才会被系统接受。
     */
    fun enforce(context: Context): EnforceResult {
        if (!hasWritePermission(context)) {
            return EnforceResult(false, emptyList(), "缺少 WRITE_SECURE_SETTINGS 权限")
        }
        val resolver = context.contentResolver
        val fixed = mutableListOf<String>()
        var error: String? = null
        try {
            if (rawValue(resolver, SettingKeys.DEVELOPMENT_SETTINGS_ENABLED)?.trim()?.toIntOrNull() != 1) {
                Settings.Global.putInt(resolver, SettingKeys.DEVELOPMENT_SETTINGS_ENABLED, 1)
                fixed += "开发者选项"
            }
            if (rawValue(resolver, SettingKeys.ADB_WIFI_ENABLED)?.trim()?.toIntOrNull() != 1) {
                Settings.Global.putInt(resolver, SettingKeys.ADB_WIFI_ENABLED, 1)
                fixed += "无线调试"
            }
            if (rawValue(resolver, SettingKeys.ADB_ALLOWED_CONNECTION_TIME)?.trim()?.toLongOrNull() != 0L) {
                Settings.Global.putLong(resolver, SettingKeys.ADB_ALLOWED_CONNECTION_TIME, 0L)
                fixed += "adb 授权不过期"
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            Logs.e("Keeper", "写入设置失败", t)
        }
        if (fixed.isNotEmpty()) {
            Logs.i("Keeper", "已修复：${fixed.joinToString("、")}")
        }
        val verifyFailures = verify(resolver)
        lastIgnored = verifyFailures.toSet()
        if (verifyFailures.isNotEmpty()) {
            Logs.i("Keeper", "写入后回读未生效：${verifyFailures.joinToString("、")}（ROM 可能拦截）")
        }
        return EnforceResult(true, fixed, error, verifyFailures)
    }

    /** 回读校验：写入后仍不符合目标值的 key。 */
    private fun verify(resolver: ContentResolver): List<String> {
        val failures = mutableListOf<String>()
        if (rawValue(resolver, SettingKeys.DEVELOPMENT_SETTINGS_ENABLED)?.trim()?.toIntOrNull() != 1) {
            failures += SettingKeys.DEVELOPMENT_SETTINGS_ENABLED
        }
        if (rawValue(resolver, SettingKeys.ADB_WIFI_ENABLED)?.trim()?.toIntOrNull() != 1) {
            failures += SettingKeys.ADB_WIFI_ENABLED
        }
        if (rawValue(resolver, SettingKeys.ADB_ALLOWED_CONNECTION_TIME)?.trim()?.toLongOrNull() != 0L) {
            failures += SettingKeys.ADB_ALLOWED_CONNECTION_TIME
        }
        return failures
    }

    /** 针对被 ROM 拦截的 key 给出可执行提示。 */
    fun hintFor(key: String): String = when (key) {
        SettingKeys.DEVELOPMENT_SETTINGS_ENABLED ->
            "该 ROM 拒绝由应用开启开发者选项，请手动在设置中开启一次"
        SettingKeys.ADB_WIFI_ENABLED ->
            "该 ROM 拒绝由应用开启无线调试，请用磁贴或系统设置手动开启"
        SettingKeys.ADB_ALLOWED_CONNECTION_TIME ->
            "该 ROM 没有此设置的 UI 入口，只能通过电脑 adb 写入 0"
        else -> "该 ROM 拦截了此项写入"
    }
}
