package com.example.watchdog.di

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.watchdog.BuildConfig
import com.example.watchdog.data.api.DeepSeekApi
import com.example.watchdog.data.api.GlmApi
import com.example.watchdog.data.api.KimiApi
import com.example.watchdog.data.api.KimiCodeApi
import com.example.watchdog.data.api.SiliconFlowApi
import com.example.watchdog.data.local.QuotaCacheStore
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.repository.QuotaRepository
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 手动依赖注入容器，替代Hilt
 */
class AppContainer(context: Context) {

    // OkHttp
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        redactHeader("Authorization")
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Retrofit APIs
    val deepSeekApi: DeepSeekApi = createApi("https://api.deepseek.com/")
    val kimiApi: KimiApi = createApi("https://api.moonshot.cn/")
    val glmApi: GlmApi = createApi("https://bigmodel.cn/")
    val siliconFlowApi: SiliconFlowApi = createApi("https://api.siliconflow.cn/")
    val kimiCodeApi: KimiCodeApi = createApi("https://api.kimi.com/coding/")

    private inline fun <reified T> createApi(baseUrl: String): T {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(T::class.java)
    }

    // SettingsStore (需要Context)
    val settingsStore: SettingsStore = SettingsStore(context.applicationContext)

    // 主题模式（Compose 状态）：设置页修改后 MainActivity 读取该状态即时重组生效
    private val _themeMode = mutableStateOf(
        kotlinx.coroutines.runBlocking { settingsStore.getThemeMode() }
    )
    val themeMode: State<String> = _themeMode

    /** 保存并立即应用主题模式："system" / "light" / "dark" */
    suspend fun setThemeMode(mode: String) {
        settingsStore.saveThemeMode(mode)
        _themeMode.value = mode
    }

    // 额度缓存（断网回退用）
    val quotaCacheStore: QuotaCacheStore = QuotaCacheStore(context.applicationContext)

    // Repository
    val quotaRepository: QuotaRepository = QuotaRepository(
        settingsStore = settingsStore,
        cacheStore = quotaCacheStore,
        deepSeekApi = deepSeekApi,
        kimiApi = kimiApi,
        glmApi = glmApi,
        siliconFlowApi = siliconFlowApi,
        kimiCodeApi = kimiCodeApi
    )
}
