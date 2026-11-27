package com.adbkeeper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.adbkeeper.core.Keeper
import com.adbkeeper.core.Logs
import com.adbkeeper.core.ModeController
import com.adbkeeper.core.Prefs
import com.adbkeeper.core.Session
import com.adbkeeper.tile.KeeperTileService
import com.adbkeeper.ui.KeeperTheme
import com.adbkeeper.ui.StatusScreen
import com.adbkeeper.ui.onboarding.OnboardingActivity

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_START = "com.adbkeeper.action.START"
        const val ACTION_STOP = "com.adbkeeper.action.STOP"
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Logs.i("UI", "通知权限：$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初装引导：未授权且没跳过过，先进引导
        if (!Prefs.onboardingDone(this) && !Keeper.hasWritePermission(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        handleAction(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            KeeperTheme {
                StatusScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent)
    }

    /** 磁贴、小组件、快捷方式都通过 action 触发，统一走 ModeController。 */
    private fun handleAction(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_START, widgetToggleAction() -> {
                if (Keeper.hasWritePermission(this)) {
                    ModeController.start(this)
                } else {
                    Logs.i("UI", "未授权，忽略开启请求")
                }
            }
            ACTION_STOP -> ModeController.stop(this, restore = true)
            else -> Unit
        }
        KeeperTileService.refresh(this)
    }

    private fun widgetToggleAction(): String = "com.adbkeeper.action.WIDGET_TOGGLE"

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ModeController.mode(this) != ModeController.Mode.PERSISTENT) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        // 返回键退出（isFinishing）时还原；按 Home 只是进入后台，会话继续
        if (isFinishing) {
            Session.stop(this, restore = true)
        }
        super.onDestroy()
    }
}
