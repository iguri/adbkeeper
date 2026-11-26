package com.adbkeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.adbkeeper.core.Session
import com.adbkeeper.ui.KeeperTheme
import com.adbkeeper.ui.StatusScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeeperTheme {
                StatusScreen()
            }
        }
    }

    override fun onDestroy() {
        // 返回键退出（isFinishing）时还原；按 Home 只是进入后台，会话继续
        if (isFinishing) {
            Session.stop(this, restore = true)
        }
        super.onDestroy()
    }
}
