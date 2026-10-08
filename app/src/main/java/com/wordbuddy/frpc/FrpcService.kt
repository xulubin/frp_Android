package com.wordbuddy.frpc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务：承载 frpc 进程，保证后台不被系统杀死。
 * 通过 ACTION 控制启停；快捷设置磁贴与主界面都调用它。
 */
class FrpcService : Service() {

    companion object {
        const val ACTION_START = "com.wordbuddy.frpc.START"
        const val ACTION_STOP = "com.wordbuddy.frpc.STOP"
        const val ACTION_TOGGLE = "com.wordbuddy.frpc.TOGGLE"
        private const val CH_ID = "frpc_channel"
        private const val NOTI_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> doStop()
            ACTION_TOGGLE -> if (FrpcRunner.isRunning()) doStop() else doStart()
            else -> doStart()
        }
        return START_STICKY
    }

    private fun doStart() {
        startFg(buildNotification("frpc 启动中…"))
        val err = FrpcRunner.start(this)
        val text = if (err == null) "frpc 运行中" else "启动失败：$err"
        notify(buildNotification(text))
    }

    private fun doStop() {
        FrpcRunner.stop(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "FRP 隧道", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, FrpcService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("FRP 按需远控")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stopPi)
            .build()
    }

    private fun notify(n: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_ID, n)
    }
}
