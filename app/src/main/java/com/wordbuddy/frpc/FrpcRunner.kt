package com.wordbuddy.frpc

import android.content.Context
import android.util.Log
import java.io.File

/**
 * frpc 进程管理（非 root）。
 * 二进制取自 nativeLibraryDir（jniLibs 解压后的 libfrpc.so），
 * 这是 Android 10+ 唯一允许 exec 的位置。
 */
object FrpcRunner {

    private const val BIN_NAME = "libfrpc.so"

    /**
     * logcat 控制标签。root 守护订阅 main 缓冲区的该标签，
     * 因此点「启动/停止」时守护能瞬时开/关 ADB 端口（无需等待兜底轮询）。
     */
    const val CTL_TAG = "frp_light"

    @Volatile
    private var process: Process? = null

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, BIN_NAME)

    fun tomlFile(ctx: Context): File = File(ctx.filesDir, "frpc.toml")

    fun logFile(ctx: Context): File = File(ctx.filesDir, "frpc.log")

    /** 运行标记：watchdog 可选据此判定更精确的信号 */
    fun marker(ctx: Context): File = File(ctx.filesDir, "frpc.active")

    /** 进程存活判定；已退出则顺手回收（例如配置错误导致 frpc 秒退） */
    fun isRunning(): Boolean {
        val p = process ?: return false
        if (p.isAlive) return true
        process = null
        return false
    }

    /**
     * 读取 frpc 版本（执行 `libfrpc.so -v`）。
     * 注意：会 fork 子进程，建议在后台线程调用。
     */
    fun version(ctx: Context): String {
        val bin = binary(ctx)
        if (!bin.exists()) return "-"
        return try {
            val p = ProcessBuilder(bin.absolutePath, "-v")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            Regex("""(\d+\.\d+\.\d+)""").find(out)?.value ?: out.take(24).ifBlank { "-" }
        } catch (t: Throwable) {
            "-"
        }
    }

    /** 返回 null 表示启动成功，否则返回错误信息 */
    @Synchronized
    fun start(ctx: Context): String? {
        if (isRunning()) return null

        val bin = binary(ctx)
        if (!bin.exists()) {
            return "未找到二进制：${bin.absolutePath}"
        }
        if (!bin.canExecute()) runCatching { bin.setExecutable(true) }

        val cfg = ConfigStore.activeConfig(ctx)
        tomlFile(ctx).writeText(TomlBuilder.build(cfg))

        return try {
            val pb = ProcessBuilder(bin.absolutePath, "-c", tomlFile(ctx).absolutePath)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(ctx)))
            val p = pb.start()
            process = p
            runCatching { marker(ctx).writeText(System.currentTimeMillis().toString()) }
            Log.i(CTL_TAG, "state=on")
            null
        } catch (t: Throwable) {
            "启动异常：${t.message}"
        }
    }

    @Synchronized
    fun stop(ctx: Context?) {
        ctx?.let { c -> runCatching { marker(c).delete() } }
        process?.let { p ->
            runCatching { p.destroy() }
            runCatching { p.waitFor() }
        }
        process = null
        Log.i(CTL_TAG, "state=off")
    }
}
