package com.wordbuddy.frpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启（默认关闭，保持"按需"语义）。
 * 如需开机自动恢复隧道：把偏好 frpc_prefs.auto_start_on_boot 设为 true。
 * 注意：端口开关仍由 root 守护负责，这里只管 frpc 本身。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val enabled = context.getSharedPreferences("frpc_prefs", Context.MODE_PRIVATE)
            .getBoolean("auto_start_on_boot", false)
        if (!enabled) return

        val i = Intent(context, FrpcService::class.java).setAction(FrpcService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
        else context.startService(i)
    }
}
