package com.example.watchdog.di

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.watchdog.BuildConfig
import com.example.watchdog.data.api.DeepSeekApi
import com.example.watchdog.data.api.DeepSeekConsoleApi
import com.example.watchdog.data.api.GlmApi
import com.example.watchdog.data.api.GlmCodingPlanApi
import com.example.watchdog.data.api.KimiApi
import com.example.watchdog.data.api.KimiCodeApi
import com.example.watchdog.data.api.KimiConsoleApi
import com.example.watchdog.data.api.MiMoConsoleApi
import com.example.watchdog.data.api.SiliconFlowApi
import com.example.watchdog.data.local.BalanceAlertManager
import com.example.watchdog.data.local.BalanceHistoryStore
import com.example.watchdog.data.local.QuotaCacheStore
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.local.WebSessionStore
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
    // 智谱 Coding Plan 配额接口在 open.bigmodel.cn（与资源包接口不同源）
    val glmCodingPlanApi: GlmCodingPlanApi = createApi("https://open.bigmodel.cn/")
    val siliconFlowApi: SiliconFlowApi = createApi("https://api.siliconflow.cn/")
    val kimiCodeApi: KimiCodeApi = createApi("https://api.kimi.com/coding/")

    // MiMo 网页控制台内部接口（非官方，鉴权头 api-platform_ph）
    val mimoConsoleApi: MiMoConsoleApi = createApi("https://platform.xiaomimimo.com/")

    // DeepSeek 网页控制台内部接口（非官方，鉴权头 Authorization: Bearer userToken + Cookie）
    val deepSeekConsoleApi: DeepSeekConsoleApi = createApi("https://platform.deepseek.com/")

    // Kimi 控制台 SSR 页面抓取（方案乙：解析内嵌 HTML 数据）
    val kimiConsoleApi: KimiConsoleApi = createApi("https://platform.kimi.com/")

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

    // 主题模式（Compose 状态）：设置页修改后 MainActivity 读取该状态即时重组生效。
    // 初始值用同步读取，避免在 Application.onCreate 主线程上用 runBlocking 阻塞。
    private val _themeMode = mutableStateOf(settingsStore.getThemeModeSync())
    val themeMode: State<String> = _themeMode

    /** 保存并立即应用主题模式："system" / "light" / "dark" */
    suspend fun setThemeMode(mode: String) {
        settingsStore.saveThemeMode(mode)
        _themeMode.value = mode
    }

    // 额度缓存（断网回退用）
    val quotaCacheStore: QuotaCacheStore = QuotaCacheStore(context.applicationContext)

    // 网页控制台会话凭证（爬取数据源，由 WebLoginActivity 网页登录后自动抓取）
    val webSessionStore: WebSessionStore = WebSessionStore(context.applicationContext)

    // Repository
    val quotaRepository: QuotaRepository = QuotaRepository(
        settingsStore = settingsStore,
        cacheStore = quotaCacheStore,
        deepSeekApi = deepSeekApi,
        kimiApi = kimiApi,
        glmApi = glmApi,
        glmCodingPlanApi = glmCodingPlanApi,
        siliconFlowApi = siliconFlowApi,
        kimiCodeApi = kimiCodeApi,
        kimiConsoleApi = kimiConsoleApi,
        mimoConsoleApi = mimoConsoleApi,
        deepSeekConsoleApi = deepSeekConsoleApi,
        webSessionStore = webSessionStore
    )

    // 余额低水位预警
    val balanceAlertManager: BalanceAlertManager = BalanceAlertManager(
        context = context.applicationContext,
        settingsStore = settingsStore
    )

    // 余额历史快照（趋势图）
    val balanceHistoryStore: BalanceHistoryStore = BalanceHistoryStore(context.applicationContext)
}
