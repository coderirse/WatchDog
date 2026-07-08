package com.example.watchdog.di

import android.content.Context
import com.example.watchdog.data.api.DeepSeekApi
import com.example.watchdog.data.api.GlmApi
import com.example.watchdog.data.api.KimiApi
import com.example.watchdog.data.api.SiliconFlowApi
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
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        )
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Retrofit APIs
    val deepSeekApi: DeepSeekApi = createApi("https://api.deepseek.com/")
    val kimiApi: KimiApi = createApi("https://api.moonshot.cn/")
    val glmApi: GlmApi = createApi("https://open.bigmodel.cn/")
    val siliconFlowApi: SiliconFlowApi = createApi("https://api.siliconflow.cn/")

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

    // Repository
    val quotaRepository: QuotaRepository = QuotaRepository(
        settingsStore = settingsStore,
        deepSeekApi = deepSeekApi,
        kimiApi = kimiApi,
        glmApi = glmApi,
        siliconFlowApi = siliconFlowApi
    )
}
