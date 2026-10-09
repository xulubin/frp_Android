package com.wordbuddy.frpc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * 单 Activity 三页结构：
 *   0 控制页（默认）：大圆按钮 绿「启动」/ 红「停止」
 *   1 配置页：编辑"当前生效"的配置
 *   2 列表页：多配置管理 + 导入 / 导出（SAF，无需存储权限）
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val PAGE_CONTROL = 0
        const val PAGE_CONFIG = 1
        const val PAGE_PROFILES = 2
    }

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var bottomNav: BottomNavigationView

    // ---------------- 控制页 ----------------
    private lateinit var statusDot: View
    private lateinit var tvState: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var tvHint: TextView
    private lateinit var tvProfile: TextView
    private lateinit var tvServer: TextView
    private lateinit var tvTunnel: TextView
    private lateinit var tvPorts: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvBinary: TextView
    private lateinit var tvLogPreview: TextView

    // ---------------- 配置页 ----------------
    private lateinit var etProfileName: TextInputEditText
    private lateinit var etServerAddr: TextInputEditText
    private lateinit var etServerPort: TextInputEditText
    private lateinit var etToken: TextInputEditText
    private lateinit var etUser: TextInputEditText
    private lateinit var etDns: TextInputEditText
    private lateinit var etProxyName: TextInputEditText
    private lateinit var etLocalIP: TextInputEditText
    private lateinit var etLocalPort: TextInputEditText
    private lateinit var etRemotePort: TextInputEditText
    private lateinit var cbTls: CheckBox
    private lateinit var cbEnc: CheckBox
    private lateinit var cbComp: CheckBox

    // ---------------- 列表页 ----------------
    private lateinit var profileList: LinearLayout

    private var frpcVersion = "…"

    /** 编辑页是否已把配置灌进界面；未灌前禁止回写，防止用空值覆盖配置 */
    private var editorLoaded = false

    /** 已发出启动指令、等待确认结果 */
    private var pendingStart = false

    // ---------------- SAF：导出 / 导入（无需任何存储权限） ----------------
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ConfigStore.activeConfig(this).toJson().toByteArray(Charsets.UTF_8))
            }
            toast(getString(R.string.toast_exported))
        }.onFailure { toast(getString(R.string.toast_export_failed, it.message ?: "")) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("cannot read file")
            val cfg = FrpcConfig.fromJson(text)
            val created = ConfigStore.create(this, cfg.name.ifBlank { "imported" }, cfg)
            refreshProfiles()
            toast(getString(R.string.toast_imported, created))
        }.onFailure { toast(getString(R.string.toast_import_failed, it.message ?: "")) }
    }

    // =================================================================== 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.app_name)
        setupBottomNav()

        // 自愈：保证默认模板与至少一条配置存在（并迁移旧版单配置）
        ConfigStore.ensureWorking(this)
        // 关键：启动时就把配置灌进编辑框，避免"字段还空着就被回写"导致配置被清空
        loadEditor()

        // ---- 控制页 ----
        btnToggle.setOnClickListener { toggleFrpc() }
        findViewById<MaterialButton>(R.id.btnOpenLog).setOnClickListener { showLogDialog() }
        tvLogPreview.setOnClickListener { showLogDialog() }

        // ---- 配置页 ----
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val err = ConfigStore.saveActive(this, collect())
            if (err != null) {
                toast(err)
            } else {
                toast(getString(R.string.toast_saved))
                refreshControl()
            }
        }
        findViewById<MaterialButton>(R.id.btnSaveStart).setOnClickListener {
            val err = ConfigStore.saveActive(this, collect())
            if (err != null) {
                toast(err)
            } else {
                bottomNav.selectedItemId = R.id.nav_control
                startFrpc()
            }
        }
        findViewById<MaterialButton>(R.id.btnSetDefault).setOnClickListener {
            ConfigStore.saveDefaults(this, collect())
            toast(getString(R.string.toast_set_default))
        }
        findViewById<MaterialButton>(R.id.btnRestoreDefault).setOnClickListener {
            val d = ConfigStore.loadDefaults(this)
            fillEditor(d, etProfileName.text.toString().trim().ifBlank { d.name })
            toast(getString(R.string.toast_restored))
        }

        // ---- 列表页 ----
        findViewById<MaterialButton>(R.id.btnNewProfile).setOnClickListener { newProfileDialog() }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            exportLauncher.launch(getString(R.string.export_file_name))
        }

        // 后台读取 frpc 版本，避免阻塞 UI
        Thread {
            val v = FrpcRunner.version(this)
            runOnUiThread {
                frpcVersion = v
                refreshControl()
            }
        }.start()

        askNotificationPermission()
        maybeAskBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        if (viewFlipper.displayedChild == PAGE_CONTROL) refreshControl()
    }

    // =================================================================== 绑定

    private fun bindViews() {
        viewFlipper = findViewById(R.id.viewFlipper)
        bottomNav = findViewById(R.id.bottomNav)

        statusDot = findViewById(R.id.statusDot)
        tvState = findViewById(R.id.tvState)
        btnToggle = findViewById(R.id.btnToggle)
        tvHint = findViewById(R.id.tvHint)
        tvProfile = findViewById(R.id.tvProfile)
        tvServer = findViewById(R.id.tvServer)
        tvTunnel = findViewById(R.id.tvTunnel)
        tvPorts = findViewById(R.id.tvPorts)
        tvVersion = findViewById(R.id.tvVersion)
        tvBinary = findViewById(R.id.tvBinary)
        tvLogPreview = findViewById(R.id.tvLogPreview)

        etProfileName = findViewById(R.id.etProfileName)
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

        profileList = findViewById(R.id.profileList)
    }

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_control
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_control -> { showPage(PAGE_CONTROL); true }
                R.id.nav_config -> { showPage(PAGE_CONFIG); true }
                R.id.nav_list -> { showPage(PAGE_PROFILES); true }
                else -> false
            }
        }
    }

    private fun showPage(index: Int) {
        val cur = viewFlipper.displayedChild
        // 离开配置页前自动保存，防止编辑内容丢失
        if (cur == PAGE_CONFIG && index != PAGE_CONFIG) saveEditorSilently()

        if (cur != index) {
            viewFlipper.inAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            viewFlipper.outAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)
            viewFlipper.displayedChild = index
        }

        when (index) {
            PAGE_CONTROL -> refreshControl()
            PAGE_CONFIG -> loadEditor()
            PAGE_PROFILES -> refreshProfiles()
        }
    }

    // =================================================================== 控制

    private fun toggleFrpc() {
        if (FrpcRunner.isRunning()) {
            pendingStart = false
            send(FrpcService.ACTION_STOP)
            toast(getString(R.string.toast_stopped))
            viewFlipper.postDelayed({ refreshControl() }, 900)
        } else {
            startFrpc()
        }
    }

    /**
     * 只使用"已保存的配置"启动，**绝不回写编辑框**。
     * （历史 bug：这里调用过 saveActive(collect())，在编辑页尚未加载时会把整条配置清空）
     */
    private fun startFrpc() {
        val cfg = ConfigStore.activeConfig(this)
        val err = ConfigStore.configError(this, cfg)
        if (err != null) {
            toast(err)
            return
        }
        pendingStart = true
        send(FrpcService.ACTION_START)
        toast(getString(R.string.toast_started))
        viewFlipper.postDelayed({ verifyStart() }, 1500)
    }

    /** frpc 若秒退（配置错 / 二进制缺失），把日志尾部弹出来，让失败可见 */
    private fun verifyStart() {
        refreshControl()
        if (!pendingStart) return
        pendingStart = false
        if (FrpcRunner.isRunning()) return

        val log = FrpcRunner.logFile(this)
        val tail = if (log.exists()) log.readText().takeLast(1500).trim() else ""
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_start_failed)
            .setMessage(tail.ifBlank { getString(R.string.start_failed_empty) })
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    private fun refreshControl() {
        val running = FrpcRunner.isRunning()
        val cfg = ConfigStore.activeConfig(this)

        tvState.text = getString(if (running) R.string.state_running else R.string.state_stopped)
        statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.status_running else R.color.status_stopped)
        )
        btnToggle.text = getString(if (running) R.string.btn_stop else R.string.btn_start)
        btnToggle.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.action_stop else R.color.action_start)
        )
        tvHint.text = getString(if (running) R.string.hint_running else R.string.hint_start)

        tvProfile.text = getString(R.string.label_profile, cfg.name.ifBlank { "-" })
        tvServer.text = getString(R.string.label_server, cfg.serverAddr.ifBlank { "-" })
        tvTunnel.text = getString(R.string.label_tunnel, cfg.proxyName.ifBlank { "-" })
        tvPorts.text = getString(R.string.label_ports, cfg.localPort, cfg.remotePort)
        tvVersion.text = getString(R.string.label_version, frpcVersion)

        val binOk = FrpcRunner.binary(this).exists()
        tvBinary.text = getString(
            R.string.label_binary,
            getString(if (binOk) R.string.binary_ok else R.string.binary_missing)
        )

        val log = FrpcRunner.logFile(this)
        tvLogPreview.text = if (log.exists()) {
            log.readText().takeLast(600).ifBlank { getString(R.string.log_empty) }
        } else {
            getString(R.string.log_empty)
        }
    }

    private fun showLogDialog() {
        val f = FrpcRunner.logFile(this)
        val text = if (f.exists()) f.readText().takeLast(20000) else ""
        val body = TextView(this).apply {
            this.text = text.ifBlank { getString(R.string.log_empty) }
            setPadding(48, 32, 48, 32)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        }
        val sv = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(R.string.log_dialog_title)
            .setView(sv)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    private fun send(action: String) = FrpcService.send(this, action)

    // =================================================================== 配置编辑

    private fun loadEditor() {
        val name = ConfigStore.activeName(this)
        val cfg = ConfigStore.activeConfig(this)
        fillEditor(cfg, cfg.name.ifBlank { name })
        editorLoaded = true
    }

    private fun fillEditor(c: FrpcConfig, name: String) {
        etProfileName.setText(name)
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
        name = etProfileName.text.toString().trim().ifBlank { "default" },
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

    private fun saveEditorSilently() {
        if (!editorLoaded || !::etProfileName.isInitialized) return
        // 仅在内容合法时自动落盘，避免把半截内容写坏
        if (ConfigStore.configError(this, collect()) != null) return
        runCatching { ConfigStore.saveActive(this, collect()) }
    }

    // =================================================================== 列表

    private fun refreshProfiles() {
        profileList.removeAllViews()
        val names = ConfigStore.listNames(this)

        if (names.isEmpty()) {
            profileList.addView(
                TextView(this).apply {
                    text = getString(R.string.profiles_empty)
                    setPadding(8, 24, 8, 24)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
            )
            return
        }

        val active = ConfigStore.activeName(this)
        names.forEach { name ->
            val cfg = ConfigStore.load(this, name)
            val row = layoutInflater.inflate(R.layout.item_profile, profileList, false)

            row.findViewById<TextView>(R.id.itemName).text = name
            row.findViewById<TextView>(R.id.itemSub).text =
                getString(R.string.label_server, cfg.serverAddr.ifBlank { "-" }) +
                    "   ·   " +
                    getString(R.string.label_ports, cfg.localPort, cfg.remotePort)

            val isActive = name == active
            row.findViewById<TextView>(R.id.itemActive).visibility =
                if (isActive) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.itemDot).backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (isActive) R.color.status_running else R.color.status_stopped)
            )

            // 点击整行 -> 设为当前生效
            row.setOnClickListener {
                ConfigStore.setActive(this, name)
                refreshProfiles()
            }

            row.findViewById<MaterialButton>(R.id.itemEdit).setOnClickListener {
                ConfigStore.setActive(this, name)
                bottomNav.selectedItemId = R.id.nav_config
            }

            row.findViewById<MaterialButton>(R.id.itemDelete).setOnClickListener {
                if (ConfigStore.delete(this, name)) {
                    toast(getString(R.string.toast_profile_deleted, name))
                    refreshProfiles()
                    refreshControl()
                } else {
                    toast(getString(R.string.err_last_profile))
                }
            }

            profileList.addView(row)
        }
    }

    private fun newProfileDialog() {
        val input = EditText(this).apply { hint = getString(R.string.dialog_new_profile_hint) }
        val wrap = FrameLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_new_profile_title)
            .setView(wrap)
            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.err_name_empty))
                    return@setPositiveButton
                }
                val template = ConfigStore.loadDefaults(this)
                template.name = name
                val created = ConfigStore.create(this, name, template)
                toast(getString(R.string.toast_profile_created, created))
                refreshProfiles()
                bottomNav.selectedItemId = R.id.nav_config
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    // =================================================================== 权限 / 引导

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

    private fun maybeAskBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences("frpc_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_go) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            }
            .setNegativeButton(R.string.battery_later, null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
