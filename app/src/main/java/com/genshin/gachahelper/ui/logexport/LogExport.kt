package com.genshin.gachahelper.ui.logexport

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.genshin.gachahelper.ui.theme.WishShapes
import java.io.File

/**
 * 日志导出弹窗状态：包含预先生成的文件 + 日志目录路径。
 * 一次性快照，导出动作完成后即可关闭弹窗。
 */
data class LogExportDialogState(
    val logDirPath: String,
    val activeFilePath: String,
    /** 预先生成的导出文件（位于 cacheDir），用于分享面板 */
    val exportFilePath: String?,
    val exportFileSize: Long = 0,
    /** 复制文本到剪贴板后的 toast 反馈 */
    val lastAction: String? = null
)

/**
 * 把日志文件通过系统分享面板发出去（微信 / QQ / 邮件 / 蓀牙 / 复制到…）。
 *
 * 日志内容已由 [com.genshin.gachahelper.auth.AppLog] 脱敏（token/cookie 打码），
 * 分享给开发者用于定位 1034 / 验证失败等问题。
 */
fun shareLogFileViaIntent(context: Context, path: String) {
    val uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        File(path)
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "GenshinGachaHelper log ${File(path).name}")
        putExtra(Intent.EXTRA_TEXT, "附件为 App 日志（已脱敏）。请发给开发者分析。")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "分享日志文件")
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(chooser)
}

/**
 * 日志导出弹窗：底部对话框，三选一动作。
 * 1. 分享文件 → 系统分享面板
 * 2. 复制文本 → 直接粘到 IM
 * 3. 复制路径 → 把文件路径发给开发者
 *
 * 2026-09-20：从 HomeScreen 迁出为共享组件——按用户需求，该测试期诊断功能
 * 入口从首页右上角挪到「设置 → 关于」区块。
 */
@Composable
fun LogExportDialog(
    dialog: LogExportDialogState,
    onDismiss: () -> Unit,
    onShareFile: (Context) -> Unit,
    onCopyText: () -> Unit,
    onOpenPath: () -> Unit
) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "导出日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "把日志发给开发者，定位 1034 / 验证失败原因",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 文件信息卡片
                Surface(
                    color = Color(0xFFF6F8FB),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "日志目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF888888)
                        )
                        Text(
                            text = dialog.logDirPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF333333),
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                        )
                        Text(
                            text = "今日文件 · ${dialog.exportFileSize} 字节",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF888888)
                        )
                        Text(
                            text = dialog.activeFilePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF333333),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                // 动作按钮
                androidx.compose.material3.Button(
                    onClick = { onShareFile(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = WishShapes.lg,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE7C877),
                        contentColor = Color(0xFF1A1508)
                    )
                ) {
                    Text("📤 分享日志文件", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onCopyText,
                    modifier = Modifier.fillMaxWidth(),
                    shape = WishShapes.lg
                ) {
                    Text("📋 复制日志全文到剪贴板")
                }
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenPath,
                    modifier = Modifier.fillMaxWidth(),
                    shape = WishShapes.lg
                ) {
                    Text("🔗 复制文件路径到剪贴板")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭", color = Color(0xFF888888))
                }

                // 最近一次动作反馈
                dialog.lastAction?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFFFF4E5),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA36A00)
                        )
                    }
                }
            }
        }
    }
}
