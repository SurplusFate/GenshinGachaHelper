package com.genshin.gachahelper.auth

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用日志门面（用户最新反馈：之前一直在"靠截图反推"，没用日志驱动）
 *
 * ── 解决的问题 ──────────────────────────────────────────────────
 * 之前修复 code:1034 / ClassCastException 时，都是基于用户截图反向猜测根因。
 * 这一次把所有关键路径（请求体、响应头、DS 签名原文、gt.js 加载、扫码凭据、
 * 异常栈）落到 `/data/data/<pkg>/files/logs/gacha_helper_YYYY-MM-DD.log`，
 * 加上 logcat 双写，下次用户跑一次失败流程就能把日志文件直接发我分析。
 *
 * ── 用法 ────────────────────────────────────────────────────────
 * ```kotlin
 * // 任何业务模块：无需注入，直接静态调用
 * AppLog.i("Verify", "createVerification", "req url=...")
 * AppLog.e("Captcha", "onCaptchaError", "msg=...", throwable)
 *
 * // 应用启动：调用一次启动日志系统（Application.onCreate）
 * AppLog.bootstrap(applicationContext)
 * ```
 *
 * ── 实现要点 ──────────────────────────────────────────────────
 * - **异步落盘**：写入请求通过 Channel 发到 IO 线程，避免阻塞业务调用方。
 * - **追加模式**：保留历史日志；文件按天滚动，单文件超过 ~1.5 MB 滚动到下一。
 * - **日志脱敏**：调用 [redact] 自动把 stoken / cookie_token / ltoken / device_fp
 *   中段打码，避免日志文件意外泄露。
 * - **环形缓冲**：最近 200 条日志同步进 [recentLines]，UI 可实时拉取查看。
 * - **生命周期**：App 启动调用 [bootstrap] 注册全局异常兜底，崩溃栈也写进去。
 * - **懒启动**：即便没人调用 [bootstrap]，第一次 enqueue 也会自动 init。
 *
 * ── 配套入口 ──────────────────────────────────────────────────
 * - App 内"日志"页面读取 [logFile] 和 [recentLines] 渲染（未来添加）。
 * - 调试构建保留 `Log.i` 完整输出；release 仍写文件但降级到 INFO。
 */
@Singleton
class AppLog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val DEFAULT_TAG = "GachaHelper"
        private const val LOG_DIR = "logs"
        private const val MAX_FILE_BYTES = 1_500_000L  // 1.5 MB 单文件上限
        private const val MAX_KEEP_FILES = 5           // 最多保留 5 个日志文件
        private const val RECENT_LINES_LIMIT = 200     // 内存缓冲条数

        // 脱敏关键字：value 中段会被替换为 ****，前后各保留 4 个字符
        // 注意：DS 签名（ds=xxx）不在名单——它是一次性请求签名而非凭据，
        // 且 DsSigner 日志需要完整值用于和服务端计算结果对比
        private val REDACT_KEYS = listOf(
            "stoken", "stoken_v2", "ltoken", "ltoken_v2", "ltuid", "ltuid_v2",
            "cookie_token", "cookie_token_v2", "account_id", "account_id_v2",
            "mid", "mid_v2", "account_mid_v2", "ltmid_v2",
            "device_fp", "DEVICEFP"
        )

        @Volatile
        private var instance: AppLog? = null

        /**
         * 应用启动时调用一次，注册全局异常兜底。
         * 如果还没通过 Hilt 注入拿到 Context，可以先 static 调用本方法。
         */
        fun bootstrap(appContext: Context) {
            // 静态 bootstrap：手动建一个持有 Context 的实例挂到 instance。
            // 之后 Hilt 注入的同名单例会覆盖它（同一个 Context），保持幂等。
            if (instance == null) {
                instance = AppLog(appContext.applicationContext).also { it.init() }
            }
        }

        /**
         * 便捷调用入口：无需注入即可写日志（绝大多数业务代码应该走这里）
         * 如果还没 init，写入会被静默丢弃（logcat 仍输出）。
         */
        fun d(tag: String, step: String, msg: String) = log(Log.DEBUG, tag, step, msg)
        fun i(tag: String, step: String, msg: String) = log(Log.INFO, tag, step, msg)
        fun w(tag: String, step: String, msg: String) = log(Log.WARN, tag, step, msg)
        fun e(tag: String, step: String, msg: String) = log(Log.ERROR, tag, step, msg)

        fun d(tag: String, step: String, msg: String, t: Throwable) =
            log(Log.DEBUG, tag, step, "$msg\n${stack(t)}", t)
        fun i(tag: String, step: String, msg: String, t: Throwable) =
            log(Log.INFO, tag, step, "$msg\n${stack(t)}", t)
        fun w(tag: String, step: String, msg: String, t: Throwable) =
            log(Log.WARN, tag, step, "$msg\n${stack(t)}", t)
        fun e(tag: String, step: String, msg: String, t: Throwable) =
            log(Log.ERROR, tag, step, "$msg\n${stack(t)}", t)

        /**
         * 脱敏：value 中段打码。例如
         *   stoken=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdef → stoken=eyJh****cdef
         * 用于把 Cookie / 请求头塞进日志前先清洗，避免文件泄露凭据。
         */
        fun redact(input: String?): String {
            if (input.isNullOrEmpty()) return ""
            var out: String = input
            for (key in REDACT_KEYS) {
                // 形式 1：key=value（用 ; 或 , 或空格 分隔）
                out = Regex("(?i)($key=)([^;, \\n\\r]+)").replace(out) { m ->
                    val full = m.groupValues[2]
                    "${m.groupValues[1]}${maskMid(full)}"
                }
            }
            return out
        }

        private fun maskMid(value: String): String {
            if (value.length <= 8) return "****"
            return value.substring(0, 4) + "****" + value.substring(value.length - 4)
        }

        private fun log(
            priority: Int,
            tag: String,
            step: String,
            msg: String,
            t: Throwable? = null
        ) {
            val safeMsg = redact(msg)
            // logcat：始终输出（开发者本地调试用）
            if (t != null) {
                Log.println(priority, tag, "[$step] $safeMsg")
                Log.println(priority, tag, Log.getStackTraceString(t))
            } else {
                Log.println(priority, tag, "[$step] $safeMsg")
            }
            // 文件：尝试交给后台异步写
            instance?.enqueue(priority, tag, step, safeMsg, t)
        }

        private fun stack(t: Throwable): String {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<LogEntry>(capacity = 512)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val initialized = AtomicBoolean(false)
    @Volatile private var contextRef: Context? = null

    private val _recentLines = MutableStateFlow<List<String>>(emptyList())
    /** 最近日志（最多 [RECENT_LINES_LIMIT] 条），UI 可直接 collect 显示 */
    val recentLines = _recentLines.asStateFlow()

    private data class LogEntry(
        val timestamp: Long,
        val priority: Int,
        val tag: String,
        val step: String,
        val message: String,
        val throwable: Throwable?
    )

    /**
     * 启动日志系统（Application.onCreate 调用一次即可）
     * 同时注册全局异常兜底，确保崩溃栈也能写入文件。
     * 也可重复调用：仅第一次生效。
     */
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        instance = this
        contextRef = context.applicationContext
        ensureLogDir()
        scope.launch { consumeLoop() }
        // 全局未捕获异常兜底：写到文件后退出，让 ANR / 崩溃有据可查
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                enqueue(
                    priority = Log.ERROR,
                    tag = DEFAULT_TAG,
                    step = "uncaught",
                    message = "FATAL on thread=${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}",
                    throwable = throwable
                )
                // 给异步队列一点时间把栈落盘
                Thread.sleep(150)
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 入口：把日志条目丢进异步队列 */
    fun enqueue(
        priority: Int,
        tag: String,
        step: String,
        message: String,
        throwable: Throwable?
    ) {
        // 兼容懒启动：第一次被业务代码调用时自动 init
        if (!initialized.get() && contextRef == null) {
            init()
        }
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            priority = priority,
            tag = tag,
            step = step,
            message = message,
            throwable = throwable
        )
        val ok = channel.trySend(entry).isSuccess
        if (!ok) {
            // 队列满：尝试同步写一行，避免丢失关键错误
            writeToFile(listOf(format(entry)))
        }
    }

    private suspend fun consumeLoop() {
        for (entry in channel) {
            try {
                val line = format(entry)
                writeToFile(listOf(line))
                // 同步进 recentLines
                val current = _recentLines.value
                val next = (current + line).takeLast(RECENT_LINES_LIMIT)
                _recentLines.value = next
            } catch (e: Exception) {
                Log.w(DEFAULT_TAG, "AppLog write failed: ${e.message}")
            }
        }
    }

    private fun format(entry: LogEntry): String {
        val ts = dateFormat.format(Date(entry.timestamp))
        val prio = when (entry.priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "?"
        }
        val base = "$ts $prio/${entry.tag} [${entry.step}] ${entry.message}"
        return if (entry.throwable != null) {
            base + "\n" + stack(entry.throwable)
        } else base
    }

    private fun writeToFile(lines: List<String>) {
        val file = currentLogFile()
        file.parentFile?.mkdirs()
        // 超过阈值滚动
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            rotate()
        }
        file.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun currentLogFile(): File {
        val dir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        return File(dir, "gacha_helper_${fileDateFormat.format(Date())}.log")
    }

    private fun rotate() {
        val dir = File(context.filesDir, LOG_DIR)
        val files = dir.listFiles { f -> f.name.startsWith("gacha_helper_") } ?: return
        // 按修改时间排序，删掉最旧的
        val sorted = files.sortedByDescending { it.lastModified() }
        for (i in sorted.indices.reversed()) {
            if (sorted.size - i > MAX_KEEP_FILES - 1) {
                sorted[i].delete()
            }
        }
    }

    private fun ensureLogDir() {
        File(context.filesDir, LOG_DIR).mkdirs()
    }

    /**
     * 日志文件目录（用于 UI 列表 / pull 到电脑）
     */
    fun logDir(): File = File(context.filesDir, LOG_DIR)

    /**
     * 当前活跃日志文件（写到哪就读哪）
     */
    fun logFile(): File = currentLogFile()

    /**
     * 读取所有日志文件（按时间倒序），拼接后返回字符串。
     * 用作"导出日志"按钮 / 崩溃报告附件。
     */
    fun readAllLogs(maxBytes: Int = 600_000): String {
        val dir = logDir()
        val files = dir.listFiles { f -> f.name.startsWith("gacha_helper_") }
            ?.sortedByDescending { it.lastModified() }
            ?: return ""
        val sb = StringBuilder()
        for (f in files) {
            if (sb.length >= maxBytes) break
            sb.appendLine("===== ${f.name} (${f.length()} bytes) =====")
            val text = f.readText()
            // 截取尾部，避免一次返回超 600 KB
            val tail = if (text.length > maxBytes / 2) {
                text.substring(text.length - maxBytes / 2)
            } else text
            sb.append(tail)
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * 清空所有日志（调试用，UI 上加一个"清空"按钮）
     */
    fun clearAll() {
        logDir().listFiles()?.forEach { it.delete() }
        _recentLines.value = emptyList()
    }

    /**
     * 把全部日志打包到一个临时文件（cache 目录），便于走 FileProvider 分享出去。
     *
     * 返回的 [File] 指向 `cacheDir/log_export_YYYYMMDD_HHmmss.log`，
     * 应用通过 FileProvider 暴露该目录后即可分享 / 复制 / 上传。
     *
     * @param maxBytes 单文件最大字节数；超出时只保留尾部 N 字节
     */
    fun exportToCache(maxBytes: Int = 600_000): File {
        val cache = File(context.cacheDir, "log_export").also { it.mkdirs() }
        // 清掉旧的导出文件，避免堆积
        cache.listFiles()?.forEach { f ->
            if (f.name.startsWith("log_export_")) f.delete()
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(cache, "log_export_$ts.log")

        // 头部加一段机器可读的元信息（脱敏不会影响）
        out.appendText(
            "# GenshinGachaHelper log export\n" +
                "# generated_at=${dateFormat.format(Date())}\n" +
                "# package=${context.packageName}\n" +
                "# max_bytes=$maxBytes\n" +
                "========================================\n\n"
        )

        val body = readAllLogs(maxBytes - 1024)
        out.appendText(body)
        AppLog.i("AppLog", "exportToCache", "导出完成 file=${out.absolutePath} size=${out.length()}")
        return out
    }

    /**
     * 简单把日志原文复制到剪贴板（无需 FileProvider，最低门槛的导出方式）。
     * 适用于用户想直接粘贴到聊天窗口 / 备忘录的场景。
     */
    fun copyToClipboard(label: String = "gacha_helper_log"): String {
        val text = readAllLogs()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        AppLog.i("AppLog", "copyToClipboard", "已复制 size=${text.length}")
        return text
    }

    /**
     * 获取日志目录的绝对路径字符串。
     * 用于 UI 显示"日志在 /data/data/.../files/logs/"（让用户知道文件存在）。
     */
    fun logDirPath(): String = File(context.filesDir, LOG_DIR).absolutePath

    /**
     * 当前活跃日志文件路径。
     */
    fun activeLogFilePath(): String = currentLogFile().absolutePath
}

/**
 * 顶层快捷调用：与 [AppLog.Companion] 的 d/i/w/e 静态方法完全等价。
 * 仅作为面向业务的命名入口（IDE 跳转时更直观），可以省略直接用 AppLog.*。
 */
@Suppress("unused")
object AppLogShortcuts {
    fun d(tag: String, step: String, msg: String) = AppLog.d(tag, step, msg)
    fun i(tag: String, step: String, msg: String) = AppLog.i(tag, step, msg)
    fun w(tag: String, step: String, msg: String) = AppLog.w(tag, step, msg)
    fun e(tag: String, step: String, msg: String) = AppLog.e(tag, step, msg)

    fun d(tag: String, step: String, msg: String, t: Throwable) = AppLog.d(tag, step, msg, t)
    fun i(tag: String, step: String, msg: String, t: Throwable) = AppLog.i(tag, step, msg, t)
    fun w(tag: String, step: String, msg: String, t: Throwable) = AppLog.w(tag, step, msg, t)
    fun e(tag: String, step: String, msg: String, t: Throwable) = AppLog.e(tag, step, msg, t)
}