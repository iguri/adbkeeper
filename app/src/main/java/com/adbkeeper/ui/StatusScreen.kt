package com.adbkeeper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.adbkeeper.BuildConfig
import com.adbkeeper.core.AdbLink
import com.adbkeeper.core.AdbPort
import com.adbkeeper.core.Channel
import com.adbkeeper.core.ItemStatus
import com.adbkeeper.core.Keeper
import com.adbkeeper.core.Logs
import com.adbkeeper.core.NetInfo
import com.adbkeeper.core.Notifier
import com.adbkeeper.core.Prefs
import com.adbkeeper.core.PortRelay
import com.adbkeeper.core.Session
import com.adbkeeper.core.SettingKeys
import com.adbkeeper.core.Snapshot
import com.adbkeeper.core.Updater
import com.adbkeeper.core.Updater.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MutedText = Color(0xFF6B7280)
private val WarnText = Color(0xFFC2410C)

@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(Keeper.snapshot(context)) }
    var sessionActive by remember { mutableStateOf(Session.active) }
    var detectedPort by remember { mutableStateOf<String?>(null) }
    var ipv4 by remember { mutableStateOf<String?>(null) }
    var ipv6 by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notifyEnabled by remember { mutableStateOf(Prefs.notifyEnabled(context)) }
    var channel by remember { mutableStateOf(Channel.fromId(Prefs.channel(context))) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var sessionLimit by remember { mutableStateOf(Prefs.sessionLimitMinutes(context).toString()) }
    var webhook by remember { mutableStateOf(Prefs.webhook(context)) }
    var customPort by remember { mutableStateOf(Prefs.customPort(context)) }
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    BackHandler(enabled = showAbout || showGuide) {
        showAbout = false
        showGuide = false
    }
    if (showAbout) {
        AboutScreen(onBack = { showAbout = false }, onOpenGuide = { showAbout = false; showGuide = true })
        return
    }
    if (showGuide) {
        GuideScreen(onBack = { showGuide = false })
        return
    }

    val refresh: suspend () -> Unit = {
        snapshot = withContext(Dispatchers.IO) { Keeper.snapshot(context) }
        sessionActive = Session.active
        val wifiNow = snapshot.items.firstOrNull { it.key == SettingKeys.ADB_WIFI_ENABLED }?.ok == true
        if (sessionActive || wifiNow) {
            val custom = customPort.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            detectedPort = if (custom != null) null else withContext(Dispatchers.IO) { AdbPort.read(context) }
            ipv4 = withContext(Dispatchers.IO) { NetInfo.wifiIpv4(context) }
            ipv6 = withContext(Dispatchers.IO) { NetInfo.wifiIpv6(context) }
        } else {
            detectedPort = null
        }
    }

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                delay(2000)
            }
        }
    }

    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    val onToggle: () -> Unit = {
        if (!busy) {
            when {
                sessionActive -> {
                    val canRestore = snapshot.hasPermission
                    busy = true
                    Session.stop(context, restore = true)
                    scope.launch {
                        delay(400)
                        refresh()
                        busy = false
                    }
                    Toast.makeText(
                        context,
                        if (canRestore) "已结束，设置已还原" else "已结束；缺少写权限，请手动关闭无线调试",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                !snapshot.hasPermission -> {
                    Toast.makeText(context, "请先把下方授权命令复制到电脑执行", Toast.LENGTH_LONG).show()
                }
                else -> {
                    busy = true
                    AdbPort.invalidate()
                    Session.start(context)
                    scope.launch {
                        delay(1500)
                        refresh()
                        busy = false
                    }
                    Toast.makeText(context, "已开启，退出应用后自动还原", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val customValid = customPort.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val wifiOn = snapshot.items.firstOrNull { it.key == SettingKeys.ADB_WIFI_ENABLED }?.ok == true
    val showPort = sessionActive || wifiOn
    val effectivePort = if (showPort) (customValid?.toString() ?: detectedPort) else null
    val grantCommand = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    val uninstallCommand = "adb uninstall ${context.packageName}"

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "无线调试助手",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "v$version · 关于",
                    modifier = Modifier
                        .clickable { showAbout = true }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MutedText,
                )
            }

            Spacer(Modifier.height(16.dp))
            StatusTiles(snapshot)

            Spacer(Modifier.height(18.dp))
            val kc = LocalKeeper.current
            Button(
                onClick = onToggle,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = RoundedCornerShape(20.dp),
                colors = if (sessionActive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    when {
                        busy -> "正在处理…"
                        sessionActive -> "结束并还原设置"
                        else -> "一键开启无线调试"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                hintText(snapshot, sessionActive, Session.remainingSeconds(context)),
                modifier = Modifier.padding(horizontal = 4.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = hintColor(snapshot, sessionActive, kc),
            )

            if (showPort) {
                Spacer(Modifier.height(18.dp))
                PortPanel(
                    detectedPort = detectedPort,
                    effectivePort = effectivePort,
                    custom = customValid != null,
                    ipv4 = ipv4,
                    ipv6 = ipv6,
                    onCopy = onCopy,
                )
            }

            Spacer(Modifier.height(18.dp))
            CommandPanel(
                granted = snapshot.hasPermission,
                command = if (snapshot.hasPermission) uninstallCommand else grantCommand,
                onCopy = onCopy,
                onOpenGuide = { showGuide = true },
            )

            Spacer(Modifier.height(14.dp))
            SettingsPanel(
                notifyEnabled = notifyEnabled,
                onNotifyToggle = {
                    notifyEnabled = it
                    Prefs.setNotifyEnabled(context, it)
                },
                channel = channel,
                onChannelClick = { showChannelDialog = true },
                sessionLimit = sessionLimit,
                onSessionLimitChange = {
                    val filtered = it.filter { ch -> ch.isDigit() }.take(4)
                    sessionLimit = filtered
                    Prefs.setSessionLimitMinutes(context, filtered.toIntOrNull() ?: 120)
                },
                webhook = webhook,
                onWebhookChange = {
                    webhook = it
                    Prefs.setWebhook(context, it)
                },
                customPort = customPort,
                onCustomPortChange = {
                    val filtered = it.filter { ch -> ch.isDigit() }.take(5)
                    customPort = filtered
                    Prefs.setCustomPort(context, filtered)
                },
                onTestNotify = {
                    Prefs.setWebhook(context, webhook.trim())
                    Prefs.setCustomPort(context, customPort.trim())
                    Notifier.sendTest(context) { ok, detail ->
                        Toast.makeText(context, detail, Toast.LENGTH_SHORT).show()
                        Logs.i("UI", "测试通知：ok=$ok $detail")
                    }
                },
                onOpenLog = {
                    logText = Logs.dump()
                    showLog = true
                },
            )

            Spacer(Modifier.height(26.dp))
        }
    }

    if (showChannelDialog) {
        AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            title = { Text("选择通知渠道") },
            text = {
                Column {
                    Channel.entries.forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    channel = item
                                    Prefs.setChannel(context, item.id)
                                    showChannelDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (item == channel) Tag("当前")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChannelDialog = false }) { Text("取消") } },
        )
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(if (Logs.isFullMode()) "运行日志（开发版）" else "诊断日志") },
            text = {
                Box(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        logText.ifBlank { "暂无日志" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("关闭") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onCopy(logText) }) { Text("复制") }
                    TextButton(
                        onClick = {
                            Logs.clear()
                            logText = Logs.dump()
                        },
                    ) { Text("清空") }
                }
            },
        )
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, LocalKeeper.current.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}

@Composable
private fun StatusTiles(snapshot: Snapshot) {
    val kc = LocalKeeper.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        snapshot.items.forEach { item ->
            Tile(item, Modifier.weight(1f), kc.ok, kc.okBg, kc.bad, kc.badBg)
        }
    }
}

@Composable
private fun Tile(
    item: ItemStatus,
    modifier: Modifier,
    okColor: Color,
    okBg: Color,
    badColor: Color,
    badBg: Color,
) {
    val color = if (item.ok) okColor else badColor
    Surface(
        color = if (item.ok) okBg else badBg,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.height(92.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (item.ok) "开" else "关",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
            Text(item.label, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, color = color)
        }
    }
}

@Composable
private fun PortPanel(
    detectedPort: String?,
    effectivePort: String?,
    custom: Boolean,
    ipv4: String?,
    ipv6: String?,
    onCopy: (String) -> Unit,
) {
    val v4 = AdbLink.ipv4(ipv4, effectivePort)
    val v6 = AdbLink.ipv6(ipv6, effectivePort)
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("端口", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                effectivePort ?: "—",
                fontFamily = FontFamily.Monospace,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = if (effectivePort != null) MaterialTheme.colorScheme.onSurface else MutedText,
            )
            if (custom) {
                Spacer(Modifier.width(8.dp))
                Tag("自定义", Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            if (custom && detectedPort != null) {
                Text(
                    "系统 $detectedPort",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MutedText,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        if (custom) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (PortRelay.running) {
                    "中继运行中：监听 ${PortRelay.boundPort}，转发到系统端口，仅本次会话有效"
                } else {
                    "中继未启动：请确认会话已开启、三项设置正常"
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MutedText,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (v4 == null && v6 == null) {
            Text(
                if (effectivePort == null) "正在读取端口和局域网地址…" else "未连接 Wi-Fi，暂时没有可用地址",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MutedText,
            )
        } else {
            if (v4 != null) CopyLine(v4, onCopy)
            if (v6 != null) CopyLine(v6, onCopy)
        }
    }
}

@Composable
private fun CommandPanel(
    granted: Boolean,
    command: String,
    onCopy: (String) -> Unit,
    onOpenGuide: () -> Unit,
) {
    val kc = LocalKeeper.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (granted) "权限已授予" else "第 1 步：授予写设置权限",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Tag(if (granted) "已就绪" else "待授权")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (granted) {
                "不再需要时，在电脑上执行下面命令卸载，权限会一并移除："
            } else {
                "手机用数据线连电脑，打开 USB 调试后执行："
            },
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(8.dp))
        CommandBox(command = command, onCopy = onCopy)
        if (!granted) {
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onOpenGuide) {
                Text("手动开启步骤（ColorOS 需先切英文界面）", fontSize = 12.sp, color = kc.ok)
            }
        }
    }
}

@Composable
private fun CommandBox(command: String, onCopy: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                command,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { onCopy(command) }) { Text("复制", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SettingsPanel(
    notifyEnabled: Boolean,
    onNotifyToggle: (Boolean) -> Unit,
    channel: Channel,
    onChannelClick: () -> Unit,
    sessionLimit: String,
    onSessionLimitChange: (String) -> Unit,
    webhook: String,
    onWebhookChange: (String) -> Unit,
    customPort: String,
    onCustomPortChange: (String) -> Unit,
    onTestNotify: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val kc = LocalKeeper.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, kc.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChannelClick() }
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("通知渠道", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text(channel.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("开启成功后推送连接信息", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("IPv4 / IPv6（如有）与端口", fontSize = 11.sp, color = MutedText)
                }
                Switch(checked = notifyEnabled, onCheckedChange = onNotifyToggle)
            }

            if (notifyEnabled) {
                OutlinedTextField(
                    value = webhook,
                    onValueChange = onWebhookChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    label = { Text("${channel.label} Webhook") },
                    placeholder = { Text("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onTestNotify) { Text("发送测试", fontSize = 12.sp) }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = kc.hairline)

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("会话自动结束", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("单位分钟，到点自动还原设置；0 表示不限", fontSize = 11.sp, color = MutedText)
                }
                OutlinedTextField(
                    value = sessionLimit,
                    onValueChange = onSessionLimitChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = kc.hairline)

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自定义端口", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("固定连接端口（会话期间本机中继）", fontSize = 11.sp, color = MutedText)
                }
                OutlinedTextField(
                    value = customPort,
                    onValueChange = onCustomPortChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    placeholder = { Text("自动") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = kc.hairline)

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (Logs.isFullMode()) "运行日志（开发版）" else "诊断日志",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenLog) { Text("查看", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun GuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val grantCommand = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手动开启说明", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("返回", fontSize = 13.sp) }
            }

            Spacer(Modifier.height(12.dp))
            GuideStep("1", "打开开发者选项（已开可跳过）") {
                GuideText("设置 → 关于本机 → 版本信息 → 连点「版本号」7 次，按提示输入锁屏密码。")
                GuideAction("直达开发者选项") {
                    openActivity(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                }
            }

            Spacer(Modifier.height(12.dp))
            GuideStep("2", "打开「禁止权限监控」") {
                GuideText(
                    "ColorOS 16 起，中文界面会隐藏这个开关。处理方式：\n" +
                        "· 先把系统语言切成 English；\n" +
                        "· 再进开发者选项，接近底部找到 Disable system optimization" +
                        "（旧版叫 Disable permission monitoring）并打开，弹窗选允许；\n" +
                        "· 完成后可以把语言切回中文，不影响授权。",
                )
                Row {
                    GuideAction("切换系统语言") { openActivity(context, Settings.ACTION_LOCALE_SETTINGS) }
                    Spacer(Modifier.width(6.dp))
                    GuideAction("打开开发者选项") {
                        openActivity(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            GuideStep("3", "执行授权命令") {
                GuideText("打开 USB 调试，用数据线连电脑，执行下面这条命令。授权一次即可，重启不用重做：")
                Spacer(Modifier.height(8.dp))
                CommandBox(command = grantCommand) { text ->
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            }

            Spacer(Modifier.height(12.dp))
            GuideStep("4", "其他品牌提示") {
                GuideText(
                    "· 小米 / Redmi（MIUI、HyperOS）：开发者选项里打开「USB 调试（安全设置）」。\n" +
                        "· 华为 / 荣耀 / 原生安卓：一般可直接执行授权命令，不需要额外开关。\n" +
                        "· 如果授权成功但应用仍提示未授权，回到首页下拉刷新即可。",
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GuideStep(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(number, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun GuideText(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = MutedText)
}

@Composable
private fun GuideAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, fontSize = 12.sp) }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "没有可打开链接的应用", Toast.LENGTH_SHORT).show()
    }
}

private fun openActivity(context: Context, action: String) {    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "无法打开对应设置页", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit, onOpenGuide: () -> Unit) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<UpdateInfo?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("关于", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("返回", fontSize = 13.sp) }
            }

            Spacer(Modifier.height(10.dp))
            Panel {
                Text("无线调试助手", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MutedText,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "会话式工具：只有点「一键开启无线调试」后才临时打开三项设置，" +
                        "退出应用、划掉后台卡片或会话超时都会自动还原；不常驻后台、不申请开机自启。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MutedText,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onOpenGuide) { Text("授权与手动开启说明", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(14.dp))
            Panel {
                Text("更新通道", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    Updater.REPO_URL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(context, Updater.REPO_URL) }
                        .padding(vertical = 2.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text("读取 GitHub 最新 Release，可直接下载并安装", fontSize = 11.sp, color = MutedText)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (status.isNotEmpty()) {
                        Text(status, modifier = Modifier.weight(1f), fontSize = 12.sp, color = MutedText)
                    }
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            status = ""
                            Updater.check(BuildConfig.VERSION_NAME, BuildConfig.FLAVOR) { _, message, info ->
                                checking = false
                                status = message
                                pending = info
                            }
                        },
                    ) { Text(if (checking) "检查中…" else "检查更新", fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pending?.let { info ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("发现新版本 ${info.versionName}") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(info.notes.ifBlank { "无更新说明" }, fontSize = 12.sp, lineHeight = 18.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        val message = Updater.downloadAndInstall(context, info)
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    },
                ) { Text("下载并安装") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("稍后") } },
        )
    }
}

@Composable
private fun CopyLine(command: String, onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            command,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = { onCopy(command) }) { Text("复制", fontSize = 12.sp) }
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 10.sp, color = MutedText)
    }
}

private fun hintText(snapshot: Snapshot, active: Boolean, remainingSeconds: Long?): String {
    val timeout = remainingSeconds?.let { "；剩余 ${it / 60 + 1} 分钟自动还原" }.orEmpty()
    return when {
        active && snapshot.allOk -> "保持中：退出应用或划掉后台卡片会自动还原$timeout"
        active -> "正在恢复被关闭的设置…"
        !snapshot.hasPermission -> "先复制下方授权命令到电脑执行，再点上方按钮"
        else -> "点一下即可开启，退出应用后自动还原"
    }
}

private fun hintColor(snapshot: Snapshot, active: Boolean, kc: KeeperColors): Color = when {
    active && snapshot.allOk -> kc.ok
    active -> WarnText
    !snapshot.hasPermission -> WarnText
    else -> MutedText
}
