package com.adbkeeper.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.adbkeeper.MainActivity
import com.adbkeeper.R
import com.adbkeeper.core.Keeper
import com.adbkeeper.core.Logs
import com.adbkeeper.core.ModeController
import com.adbkeeper.service.PersistentKeeperService

/**
 * 快捷磁贴：点击在「开启 / 结束」之间切换，长按进应用。
 * 未授权时磁贴显示不可用，点击直接进引导页，不做无效动作。
 */
class KeeperTileService : TileService() {

    companion object {
        private const val TAG = "Tile"

        fun refresh(context: android.content.Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, KeeperTileService::class.java),
                )
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val snapshot = Keeper.snapshot(this)
        if (!snapshot.hasPermission) {
            Logs.i(TAG, "未授权，跳转引导")
            openApp()
            return
        }
        runCatching {
            if (ModeController.active(this)) {
                ModeController.stop(this, restore = true)
            } else {
                ModeController.start(this)
            }
        }.onFailure { Logs.e(TAG, "磁贴操作失败", it) }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val snapshot = Keeper.snapshot(this)
        val active = ModeController.active(this)
        tile.state = when {
            !snapshot.hasPermission -> Tile.STATE_UNAVAILABLE
            active && snapshot.allOk -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        val label = getString(R.string.tile_label)
        tile.label = when {
            !snapshot.hasPermission -> label
            active -> "$label · 结束"
            else -> "$label · 开启"
        }
        tile.contentDescription = when {
            !snapshot.hasPermission -> "无线调试：未授权，点击进入引导"
            active -> "无线调试：维持中，点击结束并还原"
            else -> "无线调试：已关闭，点击开启"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !snapshot.hasPermission -> "未授权"
                active && PersistentKeeperService.running -> "常驻中"
                active -> "会话中"
                else -> "已关闭"
            }
        }
        runCatching {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_keeper)
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { startActivityAndCollapse(pending) }
            .onFailure { runCatching { startActivity(intent) } }
    }
}
