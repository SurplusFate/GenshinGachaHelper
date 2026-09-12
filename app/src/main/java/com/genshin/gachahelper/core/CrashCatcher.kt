package com.genshin.gachahelper.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自述器：App 闪退瞬间把完整堆栈落盘，让问题自己开口。
 *
 * 落盘双保险：
 *  1) filesDir/crash_logs/       —— App 内部存储，供下次启动弹窗读取展示
 *  2) 公共"下载"目录              —— Android 10+ 用 MediaStore（免权限）；
 *                                  Android 8/9 尝试传统公共目录（无权限则静默跳过）
 */
object CrashCatcher {

    private const val TAG = "CrashCatcher"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prevHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    var lastSavedPath: String? = null
        private set

    /**
     * 最早窗口挂载：不依赖任何 Context，可在 Application 类加载阶段（Hilt 注入之前）
     * 调用，确保注入/初始化期的崩溃也能被捕获。幂等。
     */
    fun installHandler() {
        if (prevHandler != null) return
        prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "saveCrash failed", t)
            }
            // 保持系统默认行为：终止进程，保证系统状态一致
            prevHandler?.uncaughtException(thread, throwable) ?: run {
                Looper.getMainLooper().quit()
                Process.killProcess(Process.myPid())
            }
        }
        Log.i(TAG, "CrashCatcher handler installed")
    }

    /** 保存 Application context（attachBaseContext 阶段调用，尽可能早） */
    fun captureContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Log.i(TAG, "CrashCatcher context captured")
        }
    }

    /** 兼容入口：确保 handler 已挂载 + 保存 context。幂等。 */
    fun install(context: Context) {
        installHandler()
        captureContext(context)
    }

    private fun saveCrash(thread: Thread, throwable: Throwable) {
        val ctx = appContext ?: return
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val body = buildCrashText(ctx, thread, throwable)

        // 1) 内部存储
        val dir = File(ctx.filesDir, "crash_logs")
        dir.mkdirs()
        val internal = File(dir, "crash_$ts.txt")
        internal.writeText(body)
        lastSavedPath = internal.absolutePath
        Log.e(TAG, "crash saved: $lastSavedPath")

        // 2) 公共下载目录
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "原神抽卡助手_崩溃日志_$ts.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    Log.e(TAG, "crash also saved to Downloads: $uri")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "save crash to Downloads failed", t)
            }
        } else {
            try {
                val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (pub != null) {
                    val f = File(pub, "原神抽卡助手_崩溃日志_$ts.txt")
                    val parent = f.parentFile
                    if (parent != null && (parent.exists() || parent.mkdirs())) {
                        f.writeText(body)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "save crash legacy failed", t)
            }
        }
    }

    private fun buildCrashText(ctx: Context, thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.append("===== 原神抽卡助手 崩溃报告 =====\n")
        sb.append("时间: ").append(Date()).append('\n')
        sb.append("线程: ").append(thread.name).append('\n')
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" | Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("应用版本: ").append(
            try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Throwable) {
                "unknown"
            }
        ).append('\n')
        sb.append("异常: ").append(throwable.javaClass.name).append(": ").append(throwable.message).append("\n\n")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        sb.append(sw)
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 5) {
            sb.append("\nCaused by:\n")
            val cw = StringWriter()
            cause.printStackTrace(PrintWriter(cw))
            sb.append(cw)
            cause = cause.cause
            depth++
        }
        sb.append("\n===== END =====\n")
        return sb.toString()
    }

    /** 最近一次未处理的崩溃日志文件（App 内弹窗展示用） */
    fun latestPending(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, "crash_logs")
        if (!dir.exists()) return null
        return dir.listFiles { f -> f.isFile && f.extension == "txt" }?.maxByOrNull { it.lastModified() }
    }

    /** 标记该崩溃已处理，删除日志文件 */
    fun consumeCrash(file: File) {
        try {
            file.delete()
        } catch (_: Throwable) {
        }
    }
}
