package io.github.coderirse.watchdog.util

import io.github.coderirse.watchdog.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志（**仅 debug 构建生效**，release 下所有方法立即返回）。
 *
 * 为什么不直接用 logcat：真机诊断时设备可能掉线、logcat 缓冲区会被冲掉，
 * 而"平台内部接口抓不到数据"这类问题必须在用户操作现场取到日志。
 * 因此统一写入 `cacheDir/watchdog_debug.log`，事后用
 * `adb shell run-as <pkg> cat cache/watchdog_debug.log` 拉取即可。
 *
 * 注意：日志会包含接口路径与响应体片段，**不得记录任何凭证明文**（令牌/Cookie 只记长度与名字）。
 */
object DebugLog {

    private const val FILE_NAME = "watchdog_debug.log"
    private const val MAX_BYTES = 256 * 1024

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var truncatedThisProcess = false

    /** 由 Application.onCreate 调用，确定日志落盘位置（cacheDir 无需存储权限）。 */
    fun init(cacheDir: File) {
        if (!BuildConfig.DEBUG) return
        logFile = File(cacheDir, FILE_NAME)
    }

    fun i(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i(tag, message)
        append(tag, message)
    }

    fun d(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.d(tag, message)
        append(tag, message)
    }

    private fun append(tag: String, message: String) {
        val file = logFile ?: return
        runCatching {
            // 每次进程启动清一次上次的残留，避免历史凭证线索长期驻留
            if (!truncatedThisProcess) {
                file.delete()
                truncatedThisProcess = true
            }
            if (file.length() > MAX_BYTES) file.delete()
            val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) +
                " [" + tag + "] " + message + "\n"
            file.appendText(line)
        }
    }

    /** 导出全部日志文本（供诊断界面/命令查看）。 */
    fun readAll(): String {
        val file = logFile ?: return ""
        return runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
    }
}
