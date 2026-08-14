package com.example.watchdog.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.example.watchdog.R
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo

/** 余额预警级别。 */
enum class BalanceAlertLevel { LOW, DEPLETED }

/**
 * 判断单平台是否触发余额预警；正常返回 null。纯函数，便于单元测试。
 *
 * @param cnyThreshold CNY 余额阈值（元），用于按量付费平台
 * @param lowFractionPercent 剩余占比阈值（百分比 0~100），用于订阅配额 / Token 平台
 */
fun classifyBalanceAlert(
    q: QuotaInfo,
    cnyThreshold: Double,
    lowFractionPercent: Double
): BalanceAlertLevel? {
    // 估算平台（火山方舟）未填初始余额：不视为耗尽/偏低
    if (q.isEstimate && !q.isAvailable) return null
    if (!q.isAvailable) return BalanceAlertLevel.DEPLETED

    // 订阅配额 / GLM 等有剩余占比的平台：按最低窗口剩余占比判断
    val fraction = q.lowestRemainingFraction
    if (fraction != null) {
        val lowFraction = (lowFractionPercent / 100.0).coerceIn(0.0, 1.0)
        return when {
            fraction <= 0.0 -> BalanceAlertLevel.DEPLETED
            fraction < lowFraction -> BalanceAlertLevel.LOW
            else -> null
        }
    }

    // 按量付费 CNY 平台：余额低于阈值
    if (q.currency == "CNY") {
        val balance = q.totalBalance.toDoubleOrNull()
        if (balance != null && balance < cnyThreshold) return BalanceAlertLevel.LOW
    }
    return null
}

/**
 * 余额低水位预警：在每次（手动/自动）刷新后评估各平台额度状态，
 * 对"新进入"耗尽或偏低状态的平台发送本地通知。
 *
 * 通过持久化的已预警集合做去重：仅在状态变化（正常 → 偏低/耗尽）时通知，
 * 状态回正常后自动清除记录，下次再降级会重新提醒，避免每次自动刷新重复打扰。
 */
class BalanceAlertManager(
    private val context: Context,
    private val settingsStore: SettingsStore
) {
    private val alertPrefs = context.applicationContext.getSharedPreferences(
        "watchdog_alert_state",
        Context.MODE_PRIVATE
    )

    private data class Alert(val platform: PlatformType, val level: BalanceAlertLevel)

    /** 评估额度并发送预警通知；需在协程中调用。 */
    suspend fun evaluate(quotas: List<QuotaInfo>) {
        if (!settingsStore.isBalanceAlertEnabled()) return
        val cnyThreshold = settingsStore.getBalanceAlertThreshold()
        val lowFractionPercent = settingsStore.getBalanceAlertFraction()

        val alreadyAlerted = alertPrefs.getStringSet(KEY_ALERTED, emptySet()) ?: emptySet()
        val currentAlerted = mutableSetOf<String>()
        val newAlerts = mutableListOf<Alert>()

        for (q in quotas) {
            // 仅基于本次成功获取的数据预警：跳过未配置、查询异常与离线缓存（isStale）数据，
            // 避免断网时用旧缓存误报。
            if (!q.isConfigured || q.errorMessage != null || q.isStale) continue
            val level = classifyBalanceAlert(q, cnyThreshold, lowFractionPercent) ?: continue
            val key = "${q.platform.name}:${level.name.lowercase()}"
            currentAlerted.add(key)
            if (key !in alreadyAlerted) {
                newAlerts += Alert(q.platform, level)
            }
        }

        // 持久化当前预警集合：状态回正常后自动清除，下次再降级会重新通知
        alertPrefs.edit { putStringSet(KEY_ALERTED, currentAlerted) }

        if (newAlerts.isEmpty()) return
        postNotification(newAlerts)
    }

    private fun postNotification(alerts: List<Alert>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // areNotificationsEnabled() 在 API 24+ 可用（minSdk 24）；Android 13+ 未授权通知权限时也返回 false
        if (!nm.areNotificationsEnabled()) return

        createChannelIfNeeded()

        val title = context.getString(R.string.balance_alert_title)
        val text = alerts.joinToString("；") { a ->
            val status = if (a.level == BalanceAlertLevel.DEPLETED)
                context.getString(R.string.balance_alert_depleted)
            else context.getString(R.string.balance_alert_low)
            "${a.platform.displayName}$status"
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        runCatching {
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.balance_alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.balance_alert_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "balance_alerts"
        const val NOTIFICATION_ID = 1001
        const val KEY_ALERTED = "alerted_platforms"
    }
}
