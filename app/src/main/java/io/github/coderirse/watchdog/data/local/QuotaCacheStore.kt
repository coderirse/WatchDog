package io.github.coderirse.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.coderirse.watchdog.BuildConfig
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.QuotaCache
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 最近一次成功获取的额度数据缓存。
 * 断网或请求异常时，用于回退展示旧数据并标记 isStale。
 *
 * 缓存键携带应用 versionCode：Gson 反序列化不走 Kotlin 构造默认值，
 * 旧版本 schema 写入的 JSON 缺字段时会向非空字段注入 null（脏数据雷区）。
 * 键带版本号后，版本升级即自然失效旧缓存，首刷走真实请求重建。
 */
class QuotaCacheStore(context: Context) : QuotaCache {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "watchdog_cache",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    override suspend fun get(platform: PlatformType): QuotaInfo? {
        return withContext(Dispatchers.IO) {
            val json = prefs.getString(key(platform), null) ?: return@withContext null
            runCatching { gson.fromJson(json, QuotaInfo::class.java) }.getOrNull()
        }
    }

    override suspend fun put(platform: PlatformType, quota: QuotaInfo) {
        withContext(Dispatchers.IO) {
            prefs.edit { putString(key(platform), gson.toJson(quota)) }
        }
    }

    private fun key(platform: PlatformType): String =
        "quota_v${BuildConfig.VERSION_CODE}_${platform.name}"
}
