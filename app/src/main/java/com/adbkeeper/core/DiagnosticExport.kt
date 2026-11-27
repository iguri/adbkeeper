package com.adbkeeper.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把日志与设备状态打包成一个 txt，通过系统分享导出。 */
object DiagnosticExport {

    fun export(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(app.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "adbkeeper-$stamp.txt")

        val snapshot = Keeper.snapshot(app)
        val header = buildString {
            appendLine("无线调试助手诊断信息")
            appendLine("时间：$stamp")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
            appendLine("包名：${app.packageName}")
            appendLine("版本：${app.packageManager.getPackageInfo(app.packageName, 0).versionName}")
            appendLine("模式：${ModeController.mode(app).label}")
            appendLine("写权限：${snapshot.hasPermission}")
            appendLine("会话中：${Session.active}，常驻中：${com.adbkeeper.service.PersistentKeeperService.running}")
            snapshot.items.forEach { item ->
                appendLine(
                    "  ${item.label}：raw=${item.raw} ok=${item.ok} ignored=${item.writtenButIgnored}",
                )
            }
            appendLine("---- 日志 ----")
        }
        file.writeText(header + "\n" + Logs.dump())

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "无线调试助手诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(Intent.createChooser(share, "导出诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Logs.i("Export", "已导出诊断文件 ${file.name}")
        true
    }.getOrElse {
        Logs.e("Export", "导出失败", it)
        false
    }
}
