package com.genshin.gachahelper.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "首页", Icons.Default.Home)
    data object History : Screen("history", "历史", Icons.Default.History)
    data object Stats : Screen("stats", "统计", Icons.Default.BarChart)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
    data object Auth : Screen("auth", "授权登录", Icons.Default.Home)
    data object Report : Screen("report", "抽卡报告", Icons.Default.BarChart)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.History,
    Screen.Stats,
    Screen.Settings
)
