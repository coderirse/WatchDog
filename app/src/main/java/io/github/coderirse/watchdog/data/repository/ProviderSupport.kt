package io.github.coderirse.watchdog.data.repository

import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import java.util.Locale

/** 各 Provider 共用的错误映射与金额格式化。 */
internal object ProviderSupport {

    fun httpError(platform: PlatformType, code: Int): QuotaInfo = when (code) {
        401 -> QuotaInfo.error(platform, "API Key 无效（HTTP 401），请检查后重新配置")
        402 -> QuotaInfo.error(platform, "账户余额不足（HTTP 402）")
        else -> QuotaInfo.error(platform, "HTTP $code")
    }

    /** HTTP 200 但响应体缺失/为空——各 Provider 统一文案，避免四处重复字面量。 */
    fun emptyResponse(platform: PlatformType): QuotaInfo =
        QuotaInfo.error(platform, "接口返回为空，请稍后重试")

    /** HTTP 200 但结构无法识别（字段全缺失）——通常意味着平台接口已变更。 */
    fun unrecognizedResponse(platform: PlatformType): QuotaInfo =
        QuotaInfo.error(platform, "响应格式无法识别，接口可能已变更")

    /**
     * 金额格式化为两位小数（7.56019→7.56；null/无法解析→0.00）。
     * 千分位逗号先归一化：Kimi 控制台渲染 "1,234.56"，toDoubleOrNull 对其返回 null，
     * 旧实现会把 ≥1000 的余额坍缩成 "0.00" 且照常展示。
     */
    fun fmtAmount(v: String?): String {
        val d = v?.replace(",", "")?.trim()?.toDoubleOrNull() ?: return "0.00"
        return String.format(Locale.US, "%.2f", d)
    }

    fun fmtAmount(v: Double?): String =
        if (v == null) "0.00" else String.format(Locale.US, "%.2f", v)

    /** 月度估算用量：小于 1 分按 0 显示。 */
    fun fmtUsage(usage: Double): String =
        if (usage < 0.01) "0.00" else String.format(Locale.US, "%.2f", usage)
}
