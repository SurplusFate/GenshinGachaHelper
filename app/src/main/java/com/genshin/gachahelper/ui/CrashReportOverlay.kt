package com.genshin.gachahelper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.genshin.gachahelper.core.CrashCatcher

/**
 * 崩溃自述弹窗：启动时若检测到未处理的崩溃日志，则弹窗展示完整堆栈，
 * 提供"复制全文"（一键回传给开发者）与"知道了"（清除日志）两个动作。
 */
@Composable
fun CrashReportOverlay() {
    val appContext = LocalContext.current.applicationContext
    var crashFile by remember { mutableStateOf(CrashCatcher.latestPending()) }
    if (crashFile == null) return

    val text = remember(crashFile) { crashFile?.readText() ?: "" }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                text = "上次运行发生闪退",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                Text(
                    text = "小马已记录崩溃堆栈，请点\"复制全文\"把日志发回给我，我马上定位修复！",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                }
            ) {
                Text(if (copied) "已复制，请粘贴发回" else "复制全文")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    crashFile?.let { CrashCatcher.consumeCrash(it) }
                    crashFile = null
                }
            ) {
                Text("知道了")
            }
        }
    )
}
