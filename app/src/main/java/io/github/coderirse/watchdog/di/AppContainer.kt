package io.github.coderirse.watchdog.di

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.github.coderirse.watchdog.BuildConfig
import io.github.coderirse.watchdog.data.api.DeepSeekApi
import io.github.coderirse.watchdog.data.api.DeepSeekConsoleApi
import io.github.coderirse.watchdog.data.api.GlmApi
import io.github.coderirse.watchdog.data.api.GlmCodingPlanApi
import io.github.coderirse.watchdog.data.api.KimiApi
import io.github.coderirse.watchdog.data.api.KimiCodeApi
import io.github.coderirse.watchdog.data.api.KimiConsoleApi
import io.github.coderirse.watchdog.data.api.MiMoConsoleApi
import io.github.coderirse.watchdog.data.api.SiliconFlowApi
import io.github.coderirse.watchdog.data.local.BalanceAlertManager
import io.github.coderirse.watchdog.data.local.BalanceHistoryStore
import io.github.coderirse.watchdog.data.local.QuotaCacheStore
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.local.WebSessionStore
import io.github.coderirse.watchdog.data.repository.MiMoSessionVerifier
import io.github.coderirse.watchdog.data.repository.QuotaRepository
import io.github.coderirse.watchdog.data.repository.providers.DeepSeekProvider
import io.github.coderirse.watchdog.data.repository.providers.GlmProvider
import io.github.coderirse.watchdog.data.repository.providers.KimiCodeProvider
import io.github.coderirse.watchdog.data.repository.providers.KimiProvider
import io.github.coderirse.watchdog.data.repository.providers.MiMoProvider
import io.github.coderirse.watchdog.data.repository.providers.SiliconFlowProvider
import io.github.coderirse.watchdog.data.repository.providers.VolcengineArkProvider
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
        // 各数据源的鉴权头一律脱敏（官方 API 用 Authorization，控制台接口用 Cookie / api-platform_ph）
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("api-platform_ph")
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
    val glmApi: GlmApi = createApi("https://open.bigmodel.cn/")
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

    // Repository：编排层 + 各平台 Provider（新增平台 = 新增一个 Provider 并在此注册）
    // Provider 依赖 WebSessionAccess 窄接口（webSessionStore 为其实现），便于纯 JVM 测试
    val quotaRepository: QuotaRepository = QuotaRepository(
        configSource = settingsStore,
        cache = quotaCacheStore,
        sessionAccess = webSessionStore,
        providers = listOf(
            DeepSeekProvider(deepSeekApi, deepSeekConsoleApi, webSessionStore, settingsStore),
            KimiProvider(kimiApi, kimiConsoleApi, webSessionStore, settingsStore),
            GlmProvider(glmApi, glmCodingPlanApi, settingsStore),
            SiliconFlowProvider(siliconFlowApi, settingsStore),
            VolcengineArkProvider(settingsStore),
            KimiCodeProvider(kimiCodeApi, settingsStore),
            MiMoProvider(mimoConsoleApi, webSessionStore, settingsStore)
        )
    )

    // 余额低水位预警
    val balanceAlertManager: BalanceAlertManager = BalanceAlertManager(
        context = context.applicationContext,
        settingsStore = settingsStore
    )

    /**
     * MiMo 会话校验器：登录抓取时用**与抓取数据完全相同的 App 侧请求**判断凭证是否有效，
     * 取代原先"页面内 fetch + 靠 WebView.url 判断"的方案（后者会把坏凭证误判为有效并保存）。
     */
    val miMoSessionVerifier: MiMoSessionVerifier =
        MiMoSessionVerifier(mimoConsoleApi, settingsStore)

    // 余额历史快照（趋势图）
    val balanceHistoryStore: BalanceHistoryStore = BalanceHistoryStore(context.applicationContext)
}
