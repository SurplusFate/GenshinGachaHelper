package com.genshin.gachahelper.ui.report

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gachahelper.analysis.GachaReport
import com.genshin.gachahelper.analysis.GachaStatsCalculator
import com.genshin.gachahelper.auth.AuthRepository
import com.genshin.gachahelper.data.model.GachaType
import com.genshin.gachahelper.data.repository.GachaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class ReportUiState(
    val report: GachaReport? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val gachaRepository: GachaRepository,
    private val authRepository: AuthRepository,
    private val statsCalculator: GachaStatsCalculator,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    init {
        loadReport()
    }

    private fun loadReport() {
        viewModelScope.launch {
            val uid = authRepository.getUid()
            val account = gachaRepository.getAccountByUid(uid ?: "")

            if (account == null) {
                _uiState.value = ReportUiState(isLoading = false)
                return@launch
            }

            val characterRecords = gachaRepository.getRecordsByPool(
                account.id, GachaType.CHARACTER.value
            )
            val weaponRecords = gachaRepository.getRecordsByPool(
                account.id, GachaType.WEAPON.value
            )
            val standardRecords = gachaRepository.getRecordsByPool(
                account.id, GachaType.STANDARD.value
            )

            val report = statsCalculator.generateReport(
                characterRecords = characterRecords,
                weaponRecords = weaponRecords,
                standardRecords = standardRecords
            )

            _uiState.value = ReportUiState(report = report, isLoading = false)
        }
    }

    /**
     * 保存报告为图片
     * 实际实现中需要将 Compose 内容渲染为 Bitmap
     * 这里提供框架，具体渲染可使用 Compose 的 drawToBitmap 或 PixelCopy
     */
    fun saveReportImage() {
        // 实际实现：将 Composable 视图渲染为 Bitmap 并保存
        // 可使用 Compose 1.5+ 的 drawToBitmap 或 AndroidView + PixelCopy
        // 这里作为框架预留
    }

    /**
     * 分享报告
     */
    fun shareReport() {
        val report = _uiState.value.report ?: return

        val shareText = buildString {
            appendLine("【原神抽卡报告】")
            appendLine()
            appendLine("累计抽数：${report.totalPulls} 抽")
            appendLine("五星总数：${report.totalFiveStars} 个")
            appendLine("平均出金：${String.format("%.1f", report.avgPullsPerFiveStar)} 抽")
            appendLine("最欧记录：${report.bestLuck} 抽")
            appendLine("最非记录：${report.worstLuck} 抽")
            appendLine("UP 成功率：${String.format("%.1f", report.upSuccessRate * 100)}%")
            appendLine()
            appendLine("—— 原神抽卡助手")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_TITLE, "原神抽卡报告")
        }

        val chooser = Intent.createChooser(intent, "分享抽卡报告").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
