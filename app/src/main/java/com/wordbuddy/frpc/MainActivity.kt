package com.wordbuddy.frpc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etServerAddr: EditText
    private lateinit var etServerPort: EditText
    private lateinit var etToken: EditText
    private lateinit var etUser: EditText
    private lateinit var etDns: EditText
    private lateinit var etProxyName: EditText
    private lateinit var etLocalIP: EditText
    private lateinit var etLocalPort: EditText
    private lateinit var etRemotePort: EditText
    private lateinit var cbTls: CheckBox
    private lateinit var cbEnc: CheckBox
    private lateinit var cbComp: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        // 启动自愈：保证"默认配置"和"工作配置"都存在，绝不空手
        ConfigStore.ensureWorking(this)
        fill(ConfigStore.loadWorking(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            ConfigStore.saveWorking(this, collect())
            toast("工作配置已保存")
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ConfigStore.saveWorking(this, collect())
            send(FrpcService.ACTION_START)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            send(FrpcService.ACTION_STOP)
        }
        findViewById<Button>(R.id.btnSaveAsDefault).setOnClickListener {
            ConfigStore.saveDefaults(this, collect())
            toast("已设为【默认配置】（主+备份+偏好 三写，不会丢）")
        }
        findViewById<Button>(R.id.btnRestoreDefault).setOnClickListener {
            val d = ConfigStore.loadDefaults(this)
            fill(d)
            ConfigStore.saveWorking(this, d)
            toast("已从【默认配置】恢复")
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener { refreshLog() }

        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun bindViews() {
        etServerAddr = findViewById(R.id.etServerAddr)
        etServerPort = findViewById(R.id.etServerPort)
        etToken = findViewById(R.id.etToken)
        etUser = findViewById(R.id.etUser)
        etDns = findViewById(R.id.etDns)
        etProxyName = findViewById(R.id.etProxyName)
        etLocalIP = findViewById(R.id.etLocalIP)
        etLocalPort = findViewById(R.id.etLocalPort)
        etRemotePort = findViewById(R.id.etRemotePort)
        cbTls = findViewById(R.id.cbTls)
        cbEnc = findViewById(R.id.cbEnc)
        cbComp = findViewById(R.id.cbComp)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
    }

    private fun fill(c: FrpcConfig) {
        etServerAddr.setText(c.serverAddr)
        etServerPort.setText(c.serverPort.toString())
        etToken.setText(c.authToken)
        etUser.setText(c.user)
        etDns.setText(c.dnsServer)
        etProxyName.setText(c.proxyName)
        etLocalIP.setText(c.localIP)
        etLocalPort.setText(c.localPort.toString())
        etRemotePort.setText(c.remotePort.toString())
        cbTls.isChecked = c.tlsEnable
        cbEnc.isChecked = c.useEncryption
        cbComp.isChecked = c.useCompression
    }

    private fun collect(): FrpcConfig = FrpcConfig(
        serverAddr = etServerAddr.text.toString().trim(),
        serverPort = etServerPort.text.toString().trim().toIntOrNull() ?: 7000,
        authToken = etToken.text.toString().trim(),
        user = etUser.text.toString().trim(),
        tlsEnable = cbTls.isChecked,
        dnsServer = etDns.text.toString().trim(),
        proxyName = etProxyName.text.toString().trim().ifBlank { "helper" },
        proxyType = "tcp",
        localIP = etLocalIP.text.toString().trim().ifBlank { "127.0.0.1" },
        localPort = etLocalPort.text.toString().trim().toIntOrNull() ?: 5555,
        remotePort = etRemotePort.text.toString().trim().toIntOrNull() ?: 60000,
        useEncryption = cbEnc.isChecked,
        useCompression = cbComp.isChecked
    )

    private fun send(action: String) {
        val i = Intent(this, FrpcService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        tvStatus.postDelayed({ refreshStatus() }, 800)
    }

    private fun refreshStatus() {
        val running = FrpcRunner.isRunning()
        val binOk = FrpcRunner.binary(this).exists()
        tvStatus.text = "状态：${if (running) "运行中" else "已停止"}   " +
                "二进制：${if (binOk) "OK" else "缺失"}"
    }

    private fun refreshLog() {
        val f = FrpcRunner.logFile(this)
        tvLog.text = if (f.exists()) f.readText().takeLast(4000) else "暂无日志"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }
}
