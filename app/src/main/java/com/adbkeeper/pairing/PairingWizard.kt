package com.adbkeeper.pairing

import android.content.Context
import android.provider.Settings
import com.adbkeeper.R
import com.adbkeeper.core.Keeper
import com.adbkeeper.core.Logs
import com.adbkeeper.core.Notifications

/**
 * 授权路径编排：电脑 ADB（首选）→ USB 辅助提示 → 本机配对（实验，暂不可用）。
 * 任何一步失败都回落到电脑 ADB，并在结果里带上可直接执行的命令。
 */
object PairingWizard {

    private const val TAG = "Pairing"

    enum class Path { PC_ADB, USB_ASSIST, LOCAL_WIRELESS }

    data class Outcome(
        val success: Boolean,
        val path: Path,
        val message: String,
        val pcCommand: String,
    )

    fun pcCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** USB 调试总开关是否已打开，用于提示"可以在电脑执行命令了"。 */
    fun usbDebuggingEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** 发一条辅助通知，把带包名的命令放到通知里，方便复制执行。 */
    fun notifyUsbAssist(context: Context) {
        val command = pcCommand(context)
        Notifications.assist(
            context,
            context.getString(R.string.pairing_pc_title),
            "在电脑执行：\n$command",
        )
        Logs.i(TAG, "已发送 USB 辅助通知")
    }

    /**
     * 本机无线配对需要 SPAKE2 + TLS-PSK，当前版本未内置该实现。
     * 这里只做能力探测与降级，不假装成功。
     */
    fun localPairingSupported(): Boolean = false

    fun fallback(context: Context, reason: String): Outcome = Outcome(
        success = Keeper.hasWritePermission(context),
        path = Path.PC_ADB,
        message = reason,
        pcCommand = pcCommand(context),
    )

    fun currentState(context: Context): Outcome {
        val granted = Keeper.hasWritePermission(context)
        return Outcome(
            success = granted,
            path = Path.PC_ADB,
            message = if (granted) "权限已授予" else "等待授权",
            pcCommand = pcCommand(context),
        )
    }
}
