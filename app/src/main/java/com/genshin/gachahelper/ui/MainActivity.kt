package com.genshin.gachahelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.genshin.gachahelper.ui.theme.GenshinGachaHelperTheme
import com.genshin.gachahelper.ui.theme.ThemeMode
import com.genshin.gachahelper.ui.theme.ThemeRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeRepository: ThemeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式系统栏：内容绘制到状态栏/导航栏后方，系统栏变透明，
        // 图标明暗随主题（背景亮度）自动切换。必须在 setContent 前调用。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // 读取当前 theme mode；FOLLOW_SYSTEM 会通过 isSystemInDarkTheme() 的系统值自动响应
            val themeMode by themeRepository.themeModeFlow
                .collectAsStateWithLifecycle(initialValue = ThemeMode.FOLLOW_SYSTEM)
            GenshinGachaHelperTheme(themeMode = themeMode) {
                // fillMaxSize 的 Surface 提供主题背景色，渗透到透明系统栏下方形成沉浸效果；
                // Scaffold 内的 TopAppBar / NavigationBar 会自动应用 statusBars / navigationBars 内边距。
                Surface(modifier = Modifier.fillMaxSize()) {
                    GachaAppNavHost()
                }
            }
        }
    }
}
