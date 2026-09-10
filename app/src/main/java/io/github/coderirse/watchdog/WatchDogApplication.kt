package io.github.coderirse.watchdog

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import io.github.coderirse.watchdog.di.AppContainer

class WatchDogApplication : Application(), ImageLoaderFactory {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }

    /**
     * 自定义 Coil ImageLoader：关闭磁盘缓存。
     *
     * 唯一图片来源是平台 Logo（LobeHub CDN / MiMo favicon），体积很小、常有内存缓存兜底，
     * 但默认的磁盘缓存会把"用户配置了哪些平台"这一信息以明文留在 cacheDir 里。
     * 不设 DiskCache 即为禁用（Coil 3 的 diskCache 默认为 null）；内存缓存保留，
     * 避免滚动时重复解码。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache(null as DiskCache?)
            .build()
}
