package com.adbkeeper.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adbkeeper.MainActivity
import com.adbkeeper.R
import com.adbkeeper.core.BatteryWhitelist
import com.adbkeeper.core.Keeper
import com.adbkeeper.core.Prefs
import com.adbkeeper.core.Snapshot
import com.adbkeeper.core.SettingKeys
import com.adbkeeper.pairing.PairingWizard
import com.adbkeeper.ui.KeeperTheme
import kotlinx.coroutines.delay

/**
 * 初装引导：检测三项设置与权限，未完成时给出直达入口。
 * 一定要留「稍后再说」出口，否则拿不到电脑的用户会被卡死在引导里。
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeeperTheme {
                OnboardingScreen(
                    onFinished = { finishToMain() },
                    onSkip = { finishToMain() },
                )
            }
        }
    }

    private fun finishToMain() {
        Prefs.setOnboardingDone(this, true)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var snapshot by remember { mutableStateOf(Keeper.snapshot(context)) }
    var compatExpanded by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(BatteryWhitelist.isIgnoring(context)) }
    var pairingMessage by remember { mutableStateOf("") }
    var usbReady by remember { mutableStateOf(PairingWizard.usbDebuggingEnabled(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = Keeper.snapshot(context)
            batteryOk = BatteryWhitelist.isIgnoring(context)
            usbReady = PairingWizard.usbDebuggingEnabled(context)
            delay(1500)
        }
    }

    val devOn = snapshot.items.firstOrNull { it.key == SettingKeys.DEVELOPMENT_SETTINGS_ENABLED }?.ok == true
    val wifiOn = snapshot.items.firstOrNull { it.key == SettingKeys.ADB_WIFI_ENABLED }?.ok == true

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.onboarding_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))

            StepCard(number = "1", title = stringResource(R.string.onboarding_step1), done = devOn) {
                BodyText(stringResource(R.string.onboarding_step1_desc))
                ActionRow {
                    Action(stringResource(R.string.onboarding_open_dev)) {
                        openActivity(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            StepCard(number = "2", title = stringResource(R.string.onboarding_step2), done = wifiOn) {
                BodyText(stringResource(R.string.onboarding_step2_desc))
                ActionRow {
                    Action(stringResource(R.string.onboarding_open_wireless)) {
                        openWireless(context)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            StepCard(
                number = "3",
                title = stringResource(R.string.onboarding_step3),
                done = snapshot.hasPermission,
            ) {
                BodyText(stringResource(R.string.onboarding_step3_desc))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pairing_pc_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                CommandBox(PairingWizard.pcCommand(context)) {
                    clipboard.setText(AnnotatedString(it))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
                if (usbReady) {
                    Spacer(Modifier.height(4.dp))
                    BodyText(stringResource(R.string.pairing_usb_hint))
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.pairing_local_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                BodyText(stringResource(R.string.pairing_local_desc))
                Spacer(Modifier.height(4.dp))
                if (!PairingWizard.localPairingSupported()) {
                    BodyText(stringResource(R.string.pairing_local_unavailable), warn = true)
                }
                if (pairingMessage.isNotEmpty()) BodyText(pairingMessage)

                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        pairingMessage = PairingWizard.fallback(
                            context,
                            context.getString(R.string.pairing_local_unavailable),
                        ).message
                        PairingWizard.notifyUsbAssist(context)
                    },
                ) { Text("发送 USB 辅助通知", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(12.dp))
            StepCard(
                number = "4",
                title = stringResource(R.string.onboarding_step4),
                done = snapshot.allOk,
            ) {
                BodyText(stringResource(R.string.onboarding_step4_desc))
                Spacer(Modifier.height(6.dp))
                SummaryRows(snapshot)
            }

            Spacer(Modifier.height(12.dp))
            CompatCard(
                expanded = compatExpanded,
                onToggle = { compatExpanded = !compatExpanded },
                onOpenLocale = { openActivity(context, Settings.ACTION_LOCALE_SETTINGS) },
                onOpenDev = { openActivity(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
            )

            Spacer(Modifier.height(12.dp))
            BatteryCard(batteryOk) {
                if (batteryOk) {
                    BatteryWhitelist.openSettings(context)
                } else {
                    BatteryWhitelist.requestExemption(context)
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_later)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onFinished,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(48.dp),
                ) { Text(stringResource(R.string.onboarding_finish), fontSize = 15.sp) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepCard(
    number: String,
    title: String,
    done: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val kc = MaterialTheme.colorScheme
    Surface(
        color = kc.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, kc.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (done) Color(0xFF0F766E) else kc.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (done) "✓" else number,
                        fontSize = 12.sp,
                        color = if (done) Color.White else kc.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BodyText(text: String, warn: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = if (warn) Color(0xFFC2410C) else Color(0xFF6B7280),
    )
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.Start) { content() }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, fontSize = 12.sp) }
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
            )
            TextButton(onClick = { onCopy(command) }) { Text("复制", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SummaryRows(snapshot: Snapshot) {
    Column {
        snapshot.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    if (item.ok) "开" else "关",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.ok) Color(0xFF0F766E) else Color(0xFFB91C1C),
                )
            }
            if (item.writtenButIgnored) {
                Text(
                    Keeper.hintFor(item.key),
                    fontSize = 11.sp,
                    color = Color(0xFFC2410C),
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun CompatCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLocale: () -> Unit,
    onOpenDev: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.onboarding_compat_title),
                    fontSize = 13.sp,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    BodyText(stringResource(R.string.onboarding_compat_body))
                    ActionRow {
                        Action(stringResource(R.string.onboarding_compat_locale), onOpenLocale)
                        Spacer(Modifier.width(4.dp))
                        Action(stringResource(R.string.onboarding_open_dev), onOpenDev)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(already: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (already) {
                    stringResource(R.string.onboarding_battery_on)
                } else {
                    stringResource(R.string.onboarding_battery)
                },
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            TextButton(onClick = onClick) {
                Text(if (already) "查看" else "去设置", fontSize = 12.sp)
            }
        }
    }
}

private fun openActivity(context: Context, action: String) {
    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "无法打开对应设置页", Toast.LENGTH_SHORT).show()
    }
}

/** 无线调试没有公开 action，失败则回退到开发者选项。 */
private fun openWireless(context: Context) {
    val ok = runCatching {
        context.startActivity(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!ok) openActivity(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
}
