package com.genshin.gachahelper.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.genshin.gachahelper.ui.auth.AuthScreen
import com.genshin.gachahelper.ui.history.HistoryScreen
import com.genshin.gachahelper.ui.home.HomeScreen
import com.genshin.gachahelper.ui.navigation.Screen
import com.genshin.gachahelper.ui.navigation.bottomNavItems
import com.genshin.gachahelper.ui.report.ReportScreen
import com.genshin.gachahelper.ui.settings.SettingsScreen
import com.genshin.gachahelper.ui.stats.StatsScreen
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GachaAppNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in bottomNavItems.map { it.route }
    val currentScreen = bottomNavItems.firstOrNull { it.route == currentDestination?.route }

    // 液态玻璃背板分层（Kyant0/AndroidLiquidGlass）：
    //  - canvasBackdrop ：背景画布层，只含底图。卡片/顶栏位于内容层内部，
    //                     让它们采样自己所在的图层 = 边录制边采样（自引用拖影），
    //                     所以页面内玻璃统一采样这一层；
    //  - contentBackdrop：页面内容层。悬浮 DOCK 位于该图层之外，
    //                     采样它即可对滚过下方的内容做真实模糊 + 折射。
    val canvasBackdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        // 1) 背景画布（被页面内玻璃采样）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .appGlassBackdrop()
                .layerBackdrop(canvasBackdrop)
        )

        // 2) 页面内容层（被悬浮 DOCK 采样）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(contentBackdrop)
        ) {
            CompositionLocalProvider(LocalGlassBackdrop provides canvasBackdrop) {
                // 全应用跟随当前主题（设置中的深色/浅色/跟随系统）。
                // 浅色模式下不再对首页做特殊外壳配色：页面背景 / 顶栏 / dock 统一使用
                // 与其它 tab 一致的浅色系，避免"暖底 + 深蓝条"的大色块割裂。
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    // 外壳透明：背景画布在下面独立成层，顶栏、卡片、DOCK 都从它上面取玻璃
                    containerColor = Color.Transparent,
                    topBar = {
                        if (currentScreen != null) {
                            GlassTopBar(title = { Text(currentScreen.title) })
                        } else if (currentDestination?.route == Screen.Auth.route) {
                            GlassTopBar(
                                title = { Text("授权登录") },
                                navigationIcon = {
                                    IconButton(onClick = { navController.navigateUp() }) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                                    }
                                }
                            )
                        } else if (currentDestination?.route == Screen.Report.route) {
                            GlassTopBar(
                                title = { Text(Screen.Report.title) },
                                navigationIcon = {
                                    IconButton(onClick = { navController.navigateUp() }) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                                    }
                                }
                            )
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route,
                        // 不再让 Dock 占用布局底部：NavHost 铺满内容区，
                        // 页面背景（含首页渐变、夜空）得以一直延伸到屏幕最底部。
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        // tab 页面切换：纯淡入淡出，轻量不位移 —— 整页 slide 会让旧/新两页同时
                        // 参与全屏位移动画，中端机切换 tab 时明显掉帧，改为纯 fade 后只做 alpha 合成。
                        // 时长压到 100/80ms：两页并存时间越短，切页帧率越稳。
                        enterTransition = {
                            fadeIn(tween(100))
                        },
                        exitTransition = {
                            fadeOut(tween(80))
                        },
                        popEnterTransition = {
                            fadeIn(tween(100))
                        },
                        popExitTransition = {
                            fadeOut(tween(80))
                        }
                    ) {
                        composable(Screen.Home.route) {
                            // 首页跟随全局主题（设置中可切换深色/浅色/跟随系统）
                            HomeScreen(navController)
                        }
                        composable(Screen.History.route) { HistoryScreen() }
                        composable(Screen.Stats.route) { StatsScreen(navController) }
                        composable(Screen.Settings.route) { SettingsScreen() }
                        composable(Screen.Auth.route) { AuthScreen(navController) }
                        composable(Screen.Report.route) { ReportScreen() }
                    }
                }
            }
        }

        // 3) 悬浮胶囊 DOCK：叠在页面内容之上，且位于"内容层"之外，
        //    因此可以对下方真实内容做液态玻璃采样（模糊 + 圆角折射）。
        if (showBottomBar) {
            val selectedIndex = bottomNavItems.indexOfFirst { screen ->
                currentDestination?.hierarchy?.any { it.route == screen.route } == true
            }
            if (selectedIndex >= 0) {
                RoundedBottomDock(
                    selectedIndex = selectedIndex,
                    onTabSelected = { index ->
                        navController.navigate(bottomNavItems[index].route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    backdrop = contentBackdrop
                )
            }
        }
    }
}
