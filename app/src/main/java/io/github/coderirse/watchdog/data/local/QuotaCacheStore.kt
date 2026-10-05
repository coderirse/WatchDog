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
 *
 * 二次防线：即使同版本内 schema 发生变化，读取也统一走 [QuotaInfoDeserializer]
 * （集合→空列表、字符串→安全默认值、枚举→SERVER），杜绝"离线兜底数据本身就是脏数据"。
 */
class QuotaCacheStore(context: Context) : QuotaCache {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "watchdog_cache",
        Context.MODE_PRIVATE
    )

    /** 写入用普通 Gson；读取用带规范化适配器的实例，保证输出对 UI 是类型安全的。 */
    private val writeGson = Gson()
    private val readGson = QuotaInfoDeserializer.cacheGson

    /** 是否已执行过本次进程内的历史键清理（幂等；@Volatile 仅为避免重复跑，竞态无害）。 */
    @Volatile
    private var purgeDone = false

    override suspend fun get(platform: PlatformType): QuotaInfo? {
        return withContext(Dispatchers.IO) {
            purgeStaleKeysOnce()
            val json = prefs.getString(key(platform), null) ?: return@withContext null
            runCatching { readGson.fromJson(json, QuotaInfo::class.java) }.getOrNull()
        }
    }

    override suspend fun put(platform: PlatformType, quota: QuotaInfo) {
        withContext(Dispatchers.IO) {
            purgeStaleKeysOnce()
            prefs.edit { putString(key(platform), writeGson.toJson(quota)) }
        }
    }

    private fun key(platform: PlatformType): String =
        "quota_v${BuildConfig.VERSION_CODE}_${platform.name}"

    /**
     * 删除非当前 versionCode 的历史缓存键（幂等，无旧键时不写入）。
     * 清理不紧急，挂在首个 get/put 的 IO 协程里顺带执行——旧实现在 init 里同步跑
     * `prefs.all`，而本类构造于 Application.onCreate 主线程路径，SharedPreferences
     * 首次访问会同步从磁盘加载整个 XML，构成启动期主线程磁盘 I/O（StrictMode 违例）。
     */
    private fun purgeStaleKeysOnce() {
        if (purgeDone) return
        purgeDone = true
        runCatching {
            val currentPrefix = "quota_v${BuildConfig.VERSION_CODE}_"
            val stale = prefs.all.keys.filter { it.startsWith(PREFIX_QUOTA) && !it.startsWith(currentPrefix) }
            if (stale.isEmpty()) return
            prefs.edit { stale.forEach { remove(it) } }
        }
    }

    private companion object {
        const val PREFIX_QUOTA = "quota_v"
    }
}
