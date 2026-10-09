package com.wordbuddy.frpc

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * 快捷设置磁贴：下拉通知栏 -> 点一下 -> 开/关 frpc。
 * 磁贴会显示"运行中/已停止"状态角标（STATE_ACTIVE + 副标题）。
 */
@RequiresApi(Build.VERSION_CODES.N)
class FrpcTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        syncTile()
    }

    override fun onClick() {
        super.onClick()

        // 走统一入口，内部已处理 Android 12+ 后台启动前台服务被拒的情况
        FrpcService.send(this, FrpcService.ACTION_TOGGLE)

        // 立即反馈（服务真正状态稍后由 onStartListening 校正）
        qsTile?.let { t ->
            val nowRunning = !FrpcRunner.isRunning()
            applyState(t, nowRunning)
            t.updateTile()
        }
    }

    private fun syncTile() {
        val t = qsTile ?: return
        applyState(t, FrpcRunner.isRunning())
        t.updateTile()
    }

    private fun applyState(t: Tile, running: Boolean) {
        t.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.subtitle = getString(if (running) R.string.state_running else R.string.state_stopped)
        }
    }
}
