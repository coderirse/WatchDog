package io.github.coderirse.watchdog.di

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 通过 CompositionLocal 向 Compose 界面提供 AppContainer，
 * 替代 WatchDogApplication.instance 全局单例，便于测试和按需替换依赖。
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer 未提供，请在 MainActivity 中用 CompositionLocalProvider 注入")
}
