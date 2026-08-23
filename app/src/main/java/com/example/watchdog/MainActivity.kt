package com.example.watchdog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.watchdog.di.LocalAppContainer
import com.example.watchdog.ui.navigation.WatchDogNavGraph
import com.example.watchdog.ui.theme.ThemeMode
import com.example.watchdog.ui.theme.WatchDogTheme
import com.example.watchdog.ui.weblogin.WebLoginActivity

class MainActivity : ComponentActivity() {

    /** 网页登录成功跳回时的"回到仪表盘"信号（onNewIntent 置位，NavGraph 消费后复位）。 */
    private val gotoDashboardSignal = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkGotoDashboard(intent)
        setContent {
            val appContainer = (application as WatchDogApplication).appContainer
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                WatchDogTheme(themeMode = ThemeMode.fromString(appContainer.themeMode.value)) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        WatchDogNavGraph(
                            gotoDashboardSignal = gotoDashboardSignal.value,
                            onGotoDashboardConsumed = { gotoDashboardSignal.value = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkGotoDashboard(intent)
    }

    private fun checkGotoDashboard(intent: Intent?) {
        if (intent?.getBooleanExtra(WebLoginActivity.EXTRA_GOTO_DASHBOARD, false) == true) {
            gotoDashboardSignal.value = true
        }
    }
}
