package io.github.coderirse.watchdog.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.ui.dashboard.DashboardScreen
import io.github.coderirse.watchdog.ui.more.MoreScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val MORE = "more"
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun WatchDogNavGraph(
    gotoDashboardSignal: Boolean = false,
    onGotoDashboardConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 网页登录成功跳回（CLEAR_TOP 回 MainActivity）后：无论当时停在哪个页面，
    // 都导航回仪表盘，由 Dashboard 重新可见时的刷新展示最新数据
    LaunchedEffect(gotoDashboardSignal) {
        if (gotoDashboardSignal) {
            navController.navigate(Routes.DASHBOARD) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            onGotoDashboardConsumed()
        }
    }

    val bottomItems = listOf(
        BottomNavItem(Routes.DASHBOARD, stringResource(R.string.nav_quota), Icons.Filled.Home),
        BottomNavItem(Routes.MORE, stringResource(R.string.nav_more), Icons.AutoMirrored.Filled.List)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomItems.forEach { item ->
                    val selected = currentRoute == item.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onNavigateToSettings = {
                        navController.navigate("settings") {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.MORE) {
                MoreScreen()
            }
            composable("settings") {
                io.github.coderirse.watchdog.ui.settings.SettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
