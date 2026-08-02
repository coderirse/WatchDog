package com.example.watchdog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.example.watchdog.di.LocalAppContainer
import com.example.watchdog.ui.navigation.WatchDogNavGraph
import com.example.watchdog.ui.theme.WatchDogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appContainer = (application as WatchDogApplication).appContainer
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                WatchDogTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        WatchDogNavGraph()
                    }
                }
            }
        }
    }
}
