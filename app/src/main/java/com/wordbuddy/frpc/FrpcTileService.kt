package com.wordbuddy.frpc

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * 快捷设置磁贴：下拉通知栏 -> 点一下 -> 开/关 frpc。
 * 这是本方案最方便的入口（无需 root）。
 */
@RequiresApi(Build.VERSION_CODES.N)
class FrpcTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        t.state = if (FrpcRunner.isRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, FrpcService::class.java).setAction(FrpcService.ACTION_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)

        // 立即反馈，稍后在 onStartListening 校正
        val t = qsTile ?: return
        t.state = if (t.state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        t.updateTile()
    }
}
