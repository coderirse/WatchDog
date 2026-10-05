package io.github.coderirse.watchdog.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
    const val SETTINGS = "settings"
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * 页面转场（原先三处 `composable()` 均无过渡，切页是硬切）：
 * - 推入设置页：轻微右滑 + 淡入，返回时对称滑出；
 * - 底部 Tab 切换：极小垂直位移 + 淡入，与页面推入/返回的手势方向语义区分开。
 * 时长控制在 150–220ms，避免拖慢感知的刷新/切换速度。
 */
private const val PUSH_IN_DURATION = 220
private const val PUSH_OUT_DURATION = 150
private const val TAB_SLIDE_DISTANCE = 24

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
            modifier = Modifier.padding(innerPadding),
            // 底部 Tab 切换的默认过渡（dashboard <-> more）：
            // 极小位移 + 淡入，弱化方向感（Tab 语义是"平级切换"而非"推入"）
            enterTransition = {
                fadeIn(tween(PUSH_IN_DURATION)) +
                    slideInVertically(tween(PUSH_IN_DURATION)) { TAB_SLIDE_DISTANCE }
            },
            exitTransition = { fadeOut(tween(PUSH_OUT_DURATION)) },
            popEnterTransition = {
                fadeIn(tween(PUSH_IN_DURATION)) +
                    slideInVertically(tween(PUSH_IN_DURATION)) { -TAB_SLIDE_DISTANCE }
            },
            popExitTransition = { fadeOut(tween(PUSH_OUT_DURATION)) }
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onNavigateToSettings = {
                        navController.navigate(Routes.SETTINGS) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.MORE) {
                MoreScreen()
            }
            composable(
                route = Routes.SETTINGS,
                // 设置页是"推入"的目的地：轻微右滑进场，返回时对称滑出
                enterTransition = {
                    fadeIn(tween(PUSH_IN_DURATION)) +
                        slideInHorizontally(tween(PUSH_IN_DURATION)) { it / 8 }
                },
                exitTransition = { fadeOut(tween(PUSH_OUT_DURATION)) },
                popEnterTransition = { fadeIn(tween(PUSH_OUT_DURATION)) },
                popExitTransition = {
                    fadeOut(tween(PUSH_OUT_DURATION)) +
                        slideOutHorizontally(tween(PUSH_OUT_DURATION)) { it / 8 }
                }
            ) {
                io.github.coderirse.watchdog.ui.settings.SettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
