package com.example.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 最近一次成功获取的额度数据缓存。
 * 断网或请求异常时，用于回退展示旧数据并标记 isStale。
 */
class QuotaCacheStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "watchdog_cache",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    suspend fun get(platform: PlatformType): QuotaInfo? {
        return withContext(Dispatchers.IO) {
            val json = prefs.getString(key(platform), null) ?: return@withContext null
            runCatching { gson.fromJson(json, QuotaInfo::class.java) }.getOrNull()
        }
    }

    suspend fun put(platform: PlatformType, quota: QuotaInfo) {
        withContext(Dispatchers.IO) {
            prefs.edit { putString(key(platform), gson.toJson(quota)) }
        }
    }

    private fun key(platform: PlatformType): String = "quota_${platform.name}"
}
