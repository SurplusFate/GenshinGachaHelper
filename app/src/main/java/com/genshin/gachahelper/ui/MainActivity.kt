package com.genshin.gachahelper.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.genshin.gachahelper.core.CrashCatcher
import com.genshin.gachahelper.signin.SignInRepository
import com.genshin.gachahelper.ui.theme.GenshinGachaHelperTheme
import com.genshin.gachahelper.ui.theme.ThemeMode
import com.genshin.gachahelper.ui.theme.ThemeRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeRepository: ThemeRepository

    @Inject
    lateinit var signInRepository: SignInRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 取证优先：若上次运行崩溃过，先直达崩溃日志页（纯 View，不走 Compose，
        // 即使闪退点在 Compose 首帧也能可靠展示），用户复制日志回传后再继续排查。
        val pendingCrash = CrashCatcher.latestPending()
        if (pendingCrash != null) {
            startActivity(CrashLogActivity.createIntent(this, pendingCrash.absolutePath))
            finish()
            return
        }

        // 沉浸式系统栏：内容绘制到状态栏/导航栏后方，系统栏变透明。
        // 注意：不在 onCreate 提前调用无参 enableEdgeToEdge()，
        // 它内部按"系统深色模式"自动附加对比 scrim —— 当系统是深色、
        // 而 App 内是浅色主题时，状态栏会被叠一层黑色遮罩（黑底白字假象）。
        // 因此统一放到 setContent 里按 App 实际主题设置全透明 scrim。
        setContent {
            // 读取当前 theme mode；FOLLOW_SYSTEM 会通过 isSystemInDarkTheme() 的系统值自动响应
            val themeMode by themeRepository.themeModeFlow
                .collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
            }
            // 系统栏透明化 + scrim 跟随 App 实际生效主题（而非系统设置），
            // 避免系统深色 + App 浅色时出现黑色遮罩；图标明暗同样跟随 App 主题。
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    // API 29+ 支持按主题给系统栏做全透明 scrim（图标颜色已手动管理）
                    if (Build.VERSION.SDK_INT >= 29) {
                        val style = SystemBarStyle.auto(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        ) { darkTheme }
                        enableEdgeToEdge(
                            statusBarStyle = style,
                            navigationBarStyle = style
                        )
                    } else {
                        // API 26-28：无 auto scrim 问题，默认 enableEdgeToEdge 即可
                        enableEdgeToEdge()
                    }
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }
            GenshinGachaHelperTheme(themeMode = themeMode) {
                // fillMaxSize 的 Surface 提供主题背景色，渗透到透明系统栏下方形成沉浸效果；
                // Scaffold 内的 TopAppBar / NavigationBar 会自动应用 statusBars / navigationBars 内边距。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GachaAppNavHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // App 回到前台补偿：自动签到开启且今日未签到 → 补签（带防抖，幂等）
        signInRepository.onLaunchOrBoot()
    }
}
