package com.wordbuddy.frpc

import android.content.Context
import java.io.File

/**
 * 配置存储 —— 三级防丢设计
 *
 * 工作配置(config.json) 读取优先级：
 *   主文件 -> backup 备份 -> 用户默认 -> 备份默认 -> SharedPreferences -> assets 出厂
 *
 * 默认配置(defaults.json) 读取优先级：
 *   主文件 -> backup 备份 -> SharedPreferences -> assets 出厂
 *
 * 写入时：主文件 + backup 备份 双写；默认配置额外写一份 SharedPreferences。
 * 另外 Manifest 开启 allowBackup + 备份规则，重装/清数据后可云恢复。
 * assets/default_config.json 随 APK 打包，永远删不掉，是最后防线。
 */
object ConfigStore {

    private const val PREF = "frpc_prefs"
    private const val PREF_DEFAULTS = "defaults_json"
    private const val PRIMARY = "config.json"
    private const val DEFAULTS = "defaults.json"
    private const val BAK_DIR = "backup"
    private const val ASSET_DEFAULT = "default_config.json"

    private fun main(ctx: Context, name: String) = File(ctx.filesDir, name)

    private fun bak(ctx: Context, name: String): File {
        val dir = File(ctx.filesDir, BAK_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$name.bak")
    }

    private fun read(f: File): FrpcConfig? = runCatching {
        if (f.exists() && f.length() > 0) FrpcConfig.fromJson(f.readText()) else null
    }.getOrNull()

    private fun write(f: File, c: FrpcConfig) {
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(c.toJson())
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 出厂默认：随 APK 打包，不可删除 */
    fun factoryDefault(ctx: Context): FrpcConfig =
        runCatching { ctx.assets.open(ASSET_DEFAULT).bufferedReader().use { it.readText() } }
            .mapCatching { FrpcConfig.fromJson(it) }
            .getOrNull() ?: FrpcConfig.fallback()

    /** 读取"默认配置"（用户可改，多层备份保护） */
    fun loadDefaults(ctx: Context): FrpcConfig {
        read(main(ctx, DEFAULTS))?.let { return it }
        read(bak(ctx, DEFAULTS))?.let { return it }
        runCatching {
            val s = prefs(ctx).getString(PREF_DEFAULTS, null)
            if (!s.isNullOrBlank()) FrpcConfig.fromJson(s) else null
        }.getOrNull()?.let { return it }
        return factoryDefault(ctx)
    }

    /** 保存"默认配置"：主 + 备份 + 偏好 三写 */
    fun saveDefaults(ctx: Context, c: FrpcConfig) {
        write(main(ctx, DEFAULTS), c)
        write(bak(ctx, DEFAULTS), c)
        runCatching {
            prefs(ctx).edit().putString(PREF_DEFAULTS, c.toJson()).apply()
        }
    }

    /** 读取"工作配置"；任何缺失都会被自动补回，绝不会返回空 */
    fun loadWorking(ctx: Context): FrpcConfig {
        read(main(ctx, PRIMARY))?.let { return it }
        read(bak(ctx, PRIMARY))?.let {
            saveWorking(ctx, it)          // 自愈：用备份补回主文件
            return it
        }
        val d = loadDefaults(ctx)
        saveWorking(ctx, d)               // 自愈：用默认补回工作配置
        return d
    }

    fun saveWorking(ctx: Context, c: FrpcConfig) {
        write(main(ctx, PRIMARY), c)
        write(bak(ctx, PRIMARY), c)
    }

    /** 启动自愈：确保"默认配置"与"工作配置"都存在且可用 */
    fun ensureWorking(ctx: Context) {
        if (read(main(ctx, DEFAULTS)) == null) saveDefaults(ctx, loadDefaults(ctx))
        loadWorking(ctx)
    }
}
