package com.wordbuddy.frpc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 配置存储（多配置 + 防丢）
 *
 * 目录结构：
 *   filesDir/configs/index.json       {"active":"main","names":["main","home"]}
 *   filesDir/configs/<name>.json      各条配置
 *   filesDir/defaults.json            默认模板（可修改）
 *   filesDir/backup/xxx.bak           所有配置与模板的备份
 *   assets/default_config.json        出厂默认（随 APK，删不掉）
 *
 * 防丢策略：
 *   - 读取时按 主文件 -> 备份 -> 默认模板 -> SharedPreferences -> assets 逐级降级
 *   - 写入时主文件与备份双写
 *   - 旧版单配置 filesDir/config.json 会在首次运行时自动迁移成第一条配置
 */
object ConfigStore {

    private const val PREF = "frpc_prefs"
    private const val PREF_DEFAULTS = "defaults_json"
    private const val DIR_CONFIGS = "configs"
    private const val INDEX = "index.json"
    private const val DEFAULTS = "defaults.json"
    private const val BAK_DIR = "backup"
    private const val ASSET_DEFAULT = "default_config.json"
    private const val LEGACY = "config.json"
    private const val ACTIVE_NAME = "default"

    // ---------------------------------------------------------------- 路径
    private fun configsDir(ctx: Context): File =
        File(ctx.filesDir, DIR_CONFIGS).apply { if (!exists()) mkdirs() }

    private fun indexFile(ctx: Context) = File(configsDir(ctx), INDEX)

    private fun profileFile(ctx: Context, name: String) =
        File(configsDir(ctx), "${safe(name)}.json")

    private fun defaultsFile(ctx: Context) = File(ctx.filesDir, DEFAULTS)

    private fun backupOf(ctx: Context, f: File): File =
        File(File(ctx.filesDir, BAK_DIR).apply { if (!exists()) mkdirs() }, f.name + ".bak")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 名称净化：去掉文件系统非法字符，保证可做文件名 */
    private fun safe(name: String): String {
        val t = name.trim()
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_', '.')
        return t.ifBlank { ACTIVE_NAME }
    }

    // ---------------------------------------------------------------- 读写
    private fun readJson(f: File): FrpcConfig? = runCatching {
        if (f.exists() && f.length() > 0) FrpcConfig.fromJson(f.readText()) else null
    }.getOrNull()

    private fun writeJson(ctx: Context, f: File, c: FrpcConfig) {
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(c.toJson())
        }
        runCatching {
            val b = backupOf(ctx, f)
            b.parentFile?.mkdirs()
            b.writeText(c.toJson())
        }
    }

    // ---------------------------------------------------------------- 索引
    private fun readNames(ctx: Context): MutableList<String> = runCatching {
        val f = indexFile(ctx)
        if (!f.exists()) return@runCatching mutableListOf()
        val arr = JSONObject(f.readText()).optJSONArray("names") ?: JSONArray()
        MutableList(arr.length()) { arr.optString(it) }
    }.getOrDefault(mutableListOf())

    private fun readActive(ctx: Context): String = runCatching {
        JSONObject(indexFile(ctx).readText()).optString("active", "")
    }.getOrDefault("")

    private fun writeIndex(ctx: Context, names: List<String>, active: String) {
        runCatching {
            val o = JSONObject()
            o.put("active", active)
            o.put("names", JSONArray(names))
            indexFile(ctx).writeText(o.toString(2))
        }
    }

    // ------------------------------------------------------------ 配置列表
    fun listNames(ctx: Context): List<String> =
        readNames(ctx).filter { profileFile(ctx, it).exists() }

    fun activeName(ctx: Context): String {
        val names = listNames(ctx)
        if (names.isEmpty()) return ""
        val a = readActive(ctx)
        return if (names.contains(a)) a else names.first()
    }

    fun setActive(ctx: Context, name: String) {
        val names = listNames(ctx)
        if (names.contains(name)) writeIndex(ctx, names, name)
    }

    fun load(ctx: Context, name: String): FrpcConfig {
        val f = profileFile(ctx, name)
        val c = readJson(f) ?: readJson(backupOf(ctx, f)) ?: loadDefaults(ctx)
        if (c.name.isBlank()) c.name = name
        return c
    }

    /** 当前生效配置（frpc 启动时使用） */
    fun activeConfig(ctx: Context): FrpcConfig {
        val n = activeName(ctx)
        return if (n.isBlank()) loadDefaults(ctx) else load(ctx, n)
    }

    /** 配置是否可用于启动；返回错误文案，null 表示通过 */
    fun configError(ctx: Context, c: FrpcConfig): String? {
        if (c.serverAddr.isBlank()) return ctx.getString(R.string.err_server_empty)
        if (c.serverPort !in 1..65535) return ctx.getString(R.string.err_server_port)
        if (c.localPort !in 1..65535) return ctx.getString(R.string.err_local_port)
        if (c.remotePort !in 1..65535) return ctx.getString(R.string.err_remote_port)
        return null
    }

    /**
     * 保存"当前生效"配置；名称改变即视为重命名。
     * 返回 null 表示成功，否则返回错误文案，且**不会改动任何已有配置**。
     *
     * 关键防护：内容不合法（如 serverAddr 为空）时直接拒绝写入。
     * 历史 bug：界面字段尚未加载就被回写，导致整条配置被清空并被改名。
     */
    fun saveActive(ctx: Context, cfg: FrpcConfig): String? {
        val c = cfg.copy()
        if (c.name.isBlank()) c.name = ACTIVE_NAME

        configError(ctx, c)?.let { return it }

        val old = activeName(ctx)
        val names = readNames(ctx).toMutableList()

        // 与其它已有配置重名 -> 自动加后缀
        if (c.name != old && names.contains(c.name)) {
            val base = c.name
            var i = 2
            while (names.contains(c.name)) { c.name = "${base}_$i"; i++ }
        }

        writeJson(ctx, profileFile(ctx, c.name), c)

        if (old.isNotEmpty() && old != c.name) {
            profileFile(ctx, old).delete()
            backupOf(ctx, profileFile(ctx, old)).delete()
            names.remove(old)
        }
        if (!names.contains(c.name)) names.add(c.name)
        writeIndex(ctx, names, c.name)
        return null
    }

    /** 新建配置，返回最终名称（重名自动加后缀） */
    fun create(ctx: Context, desired: String, cfg: FrpcConfig): String {
        val names = readNames(ctx).toMutableList()
        val base = safe(desired)
        var name = base
        var i = 2
        while (names.contains(name)) { name = "${base}_$i"; i++ }

        val c = cfg.copy()
        c.name = name
        writeJson(ctx, profileFile(ctx, name), c)
        names.add(name)
        writeIndex(ctx, names, name)
        return name
    }

    fun delete(ctx: Context, name: String): Boolean {
        val names = readNames(ctx).toMutableList()
        if (names.size <= 1) return false
        names.remove(name)
        profileFile(ctx, name).delete()
        backupOf(ctx, profileFile(ctx, name)).delete()
        val active = if (readActive(ctx) == name) names.first() else readActive(ctx)
        writeIndex(ctx, names, active)
        return true
    }

    // ------------------------------------------------------------ 默认模板
    /** 出厂默认：随 APK 打包，不可删除 */
    fun factoryDefault(ctx: Context): FrpcConfig =
        runCatching { ctx.assets.open(ASSET_DEFAULT).bufferedReader().use { it.readText() } }
            .mapCatching { FrpcConfig.fromJson(it) }
            .getOrNull() ?: FrpcConfig.fallback()

    fun loadDefaults(ctx: Context): FrpcConfig {
        readJson(defaultsFile(ctx))?.let { return it }
        readJson(backupOf(ctx, defaultsFile(ctx)))?.let { return it }
        runCatching {
            val s = prefs(ctx).getString(PREF_DEFAULTS, null)
            if (!s.isNullOrBlank()) FrpcConfig.fromJson(s) else null
        }.getOrNull()?.let { return it }
        return factoryDefault(ctx)
    }

    fun saveDefaults(ctx: Context, c: FrpcConfig) {
        writeJson(ctx, defaultsFile(ctx), c)
        runCatching { prefs(ctx).edit().putString(PREF_DEFAULTS, c.toJson()).apply() }
    }

    // ---------------------------------------------------------------- 自愈
    fun ensureWorking(ctx: Context) {
        // 默认模板自愈
        if (readJson(defaultsFile(ctx)) == null) saveDefaults(ctx, loadDefaults(ctx))

        val names = listNames(ctx)
        if (names.isEmpty()) {
            // 优先迁移旧版单配置，避免老用户丢配置
            val legacy = readJson(File(ctx.filesDir, LEGACY))
            val base = legacy ?: loadDefaults(ctx)
            if (base.name.isBlank()) base.name = ACTIVE_NAME
            create(ctx, base.name, base)
        } else {
            val a = readActive(ctx)
            if (a.isBlank() || !names.contains(a)) writeIndex(ctx, names, names.first())
        }

        // 自愈：修复历史版本可能写空的"当前生效"配置（serverAddr 为空即视为损坏）
        val an = activeName(ctx)
        if (an.isNotBlank()) {
            val f = profileFile(ctx, an)
            val cur = readJson(f)
            if (cur == null || cur.serverAddr.isBlank()) {
                val healed = loadDefaults(ctx)
                healed.name = an
                writeJson(ctx, f, healed)
            }
        }
    }
}
