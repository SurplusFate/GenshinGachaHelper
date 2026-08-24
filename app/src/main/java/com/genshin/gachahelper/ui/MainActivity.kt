package com.genshin.gachahelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        super.onCreate(savedInstanceState)
        setContent {
            // 读取当前 theme mode；FOLLOW_SYSTEM 会通过 isSystemInDarkTheme() 的系统值自动响应
            val themeMode by themeRepository.themeModeFlow
                .collectAsStateWithLifecycle(initialValue = ThemeMode.FOLLOW_SYSTEM)
            GenshinGachaHelperTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GachaAppNavHost()
                }
            }
        }
    }
}
