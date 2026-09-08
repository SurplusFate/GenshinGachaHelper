package com.genshin.gachahelper.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.genshin.gachahelper.ui.theme.WishDark
import com.genshin.gachahelper.ui.theme.WishLight
import com.genshin.gachahelper.ui.theme.isWishDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GachaAppNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in bottomNavItems.map { it.route }
    val currentScreen = bottomNavItems.firstOrNull { it.route == currentDestination?.route }

    Scaffold(
        topBar = {
            val topBarColors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            )
            if (currentScreen != null) {
                TopAppBar(
                    title = { Text(currentScreen.title) },
                    colors = topBarColors
                )
            } else if (currentDestination?.route == Screen.Auth.route) {
                TopAppBar(
                    title = { Text("授权登录") },
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = topBarColors
                )
            } else if (currentDestination?.route == Screen.Report.route) {
                TopAppBar(
                    title = { Text(Screen.Report.title) },
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = topBarColors
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                val dark = isWishDark()
                NavigationBar(
                    containerColor = if (dark) WishDark.bgFloat else WishLight.bgFloat,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = NavigationBarDefaults.Elevation
                ) {
                    val scheme = MaterialTheme.colorScheme
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = scheme.onSecondary,
                                selectedTextColor = scheme.onSecondary,
                                indicatorColor = scheme.secondaryContainer,
                                unselectedIconColor = scheme.onSurfaceVariant,
                                unselectedTextColor = scheme.onSurfaceVariant
                            ),
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen(navController) }
            composable(Screen.History.route) { HistoryScreen() }
            composable(Screen.Stats.route) { StatsScreen(navController) }
            composable(Screen.Settings.route) { SettingsScreen() }
            composable(Screen.Auth.route) { AuthScreen(navController) }
            composable(Screen.Report.route) { ReportScreen() }
        }
    }
}
