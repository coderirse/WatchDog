package io.github.coderirse.watchdog.ui.theme

import androidx.compose.ui.graphics.Color

// ---------- 浅色配色（清爽商务骨架） ----------
val BrandIndigo = Color(0xFF1E3A8A)          // 主色靛蓝
val SlateGrey = Color(0xFF475569)            // 辅助石板灰
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE2E8F0)  // 卡片描边/分隔底
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)

// 状态语义色（浅色）
val SuccessGreen = Color(0xFF059669)
val WarningAmber = Color(0xFFD97706)
val ErrorRed = Color(0xFFDC2626)

// ---------- 深色配色 ----------
val BrandIndigoLight = Color(0xFF93C5FD)     // 深色主色提亮
val DarkBackground = Color(0xFF0F172A)
val DarkSurface = Color(0xFF1E293B)
val DarkSurfaceVariant = Color(0xFF334155)
val DarkTextPrimary = Color(0xFFF1F5F9)
val DarkTextSecondary = Color(0xFF94A3B8)

// 状态语义色（深色提亮）
val SuccessGreenDark = Color(0xFF34D399)
val WarningAmberDark = Color(0xFFFBBF24)
val ErrorRedDark = Color(0xFFF87171)

// ---------- 配色方案容器/描边色（原 Theme.kt 内联字面量，提升到调色板统一管理） ----------
// 浅色
val LightPrimaryContainer = Color(0xFFDBE4FF)
val LightSecondaryContainer = Color(0xFFE2E8F0)
val LightTertiaryContainer = Color(0xFFD1FAE5)
val LightOnTertiaryContainer = Color(0xFF065F46)
val LightOutline = Color(0xFFCBD5E1)
val LightOutlineVariant = Color(0xFFE2E8F0)
val LightErrorContainer = Color(0xFFFEE2E2)
val LightOnErrorContainer = Color(0xFF991B1B)
// 深色
val DarkPrimaryContainer = Color(0xFF1E3A8A)
val DarkOnPrimaryContainer = Color(0xFFDBE4FF)
val DarkSecondaryContainer = Color(0xFF334155)
val DarkTertiaryContainer = Color(0xFF065F46)
val DarkOnTertiaryContainer = Color(0xFFD1FAE5)
val DarkOutline = Color(0xFF475569)
val DarkOutlineVariant = Color(0xFF334155)
val DarkErrorContainer = Color(0xFF7F1D1D)
val DarkOnErrorContainer = Color(0xFFFEE2E2)

// 耗尽状态渐变基准色（原 PlatformQuotaCard 内联字面量）
val DepletedBase = Color(0xFFB91C1C)
