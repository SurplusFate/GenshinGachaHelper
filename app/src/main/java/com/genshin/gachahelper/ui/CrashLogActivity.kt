package com.genshin.gachahelper.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.genshin.gachahelper.core.CrashCatcher
import java.io.File

/**
 * 崩溃日志展示页（纯传统 View，完全不经过 Compose）：
 * 即使崩溃发生在 Compose 首帧渲染/应用初始化阶段，本页面也能可靠展示上次落盘的崩溃堆栈，
 * 并提供一键复制回传。
 */
class CrashLogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile: File? = intent.getStringExtra(EXTRA_CRASH_PATH)?.let { File(it) }
            ?: CrashCatcher.latestPending()
        if (crashFile == null || !crashFile.exists()) {
            Toast.makeText(this, "未找到崩溃日志", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val crashText = crashFile.readText()
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), dp(32), dp(24), dp(16))
        root.setBackgroundColor(Color.rgb(10, 17, 40))

        val titleView = TextView(this)
        titleView.text = "上次运行发生闪退，崩溃堆栈已捕获"
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 18f
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.gravity = Gravity.CENTER
        titleView.setPadding(0, 0, 0, dp(8))
        root.addView(titleView)

        val scroll = ScrollView(this)
        val bodyView = TextView(this)
        bodyView.text = crashText
        bodyView.setTextColor(Color.rgb(235, 235, 235))
        bodyView.textSize = 12f
        bodyView.typeface = Typeface.MONOSPACE
        bodyView.setPadding(dp(12), dp(12), dp(12), dp(12))
        bodyView.background = ColorDrawable(Color.rgb(18, 26, 54))
        bodyView.movementMethod = ScrollingMovementMethod()
        bodyView.setTextIsSelectable(true)
        scroll.addView(
            bodyView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val copyBtn = Button(this)
        copyBtn.text = "复制全文"
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash", crashText))
            Toast.makeText(this@CrashLogActivity, "已复制，请粘贴发给小马", Toast.LENGTH_SHORT).show()
        }
        val clearBtn = Button(this)
        clearBtn.text = "知道了"
        clearBtn.setOnClickListener {
            CrashCatcher.consumeCrash(crashFile)
            Toast.makeText(this@CrashLogActivity, "日志已清除", Toast.LENGTH_SHORT).show()
            finish()
        }
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(0, dp(8), 0, 0)
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(
            btnRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)
    }

    companion object {
        private const val EXTRA_CRASH_PATH = "crash_path"

        fun createIntent(context: Context, crashPath: String): Intent =
            Intent(context, CrashLogActivity::class.java)
                .putExtra(EXTRA_CRASH_PATH, crashPath)
    }
}
