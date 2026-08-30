package io.github.coderirse.watchdog.data.model

/**
 * 单条余额历史快照（用于余额趋势图）。
 * 仅记录按 CNY 计价的平台总余额，Token/订阅平台不参与。
 */
data class BalanceSnapshot(
    val timestamp: Long,
    val balance: Double
)
