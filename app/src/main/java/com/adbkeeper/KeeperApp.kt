package com.adbkeeper

import android.app.Application
import com.adbkeeper.core.Logs
import com.adbkeeper.core.NetworkWatcher
import com.adbkeeper.core.Session

class KeeperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Logs.init(this, BuildConfig.FULL_LOG)
        Session.init(this)
        NetworkWatcher.start(this)
        // 上次进程被系统直接杀掉时，补做一次还原
        Session.recoverIfLeaked(this)
    }
}
