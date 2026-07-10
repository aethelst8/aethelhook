package com.aethelhook.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AethelColorPalette(
    val bgDeep: Color,
    val bgCard: Color,
    val bgCardAlt: Color,
    val accentCyan: Color,
    val accentPurple: Color,
    val accentGreen: Color,
    val accentRed: Color,
    val accentAmber: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val divider: Color,
    val glassStart: Color,
    val glassEnd: Color,
    val glassStroke: Color,
    val blobA: Color,
    val blobB: Color,
    val isDark: Boolean
)

val DarkPalette = AethelColorPalette(
    bgDeep        = Color(0xFF000000),
    bgCard        = Color(0xFF0C0C0C),
    bgCardAlt     = Color(0xFF141414),
    accentCyan    = Color(0xFF00CFFF),
    accentPurple  = Color(0xFF8B5CF6),
    accentGreen   = Color(0xFF00D68F),
    accentRed     = Color(0xFFFF4757),
    accentAmber   = Color(0xFFFFA502),
    textPrimary   = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF888888),
    textMuted     = Color(0xFF444444),
    divider       = Color(0xFF1C1C1C),
    glassStart    = Color(0x08FFFFFF),
    glassEnd      = Color(0x03FFFFFF),
    glassStroke   = Color(0x28FFFFFF),
    blobA         = Color(0x308B5CF6),
    blobB         = Color(0x1A00CFFF),
    isDark        = true
)

val LightPalette = AethelColorPalette(
    bgDeep        = Color(0xFFF0F2FF),
    bgCard        = Color(0xFFFFFFFF),
    bgCardAlt     = Color(0xFFF5F7FF),
    accentCyan    = Color(0xFF0284C7),
    accentPurple  = Color(0xFF7C3AED),
    accentGreen   = Color(0xFF059669),
    accentRed     = Color(0xFFDC2626),
    accentAmber   = Color(0xFFD97706),
    textPrimary   = Color(0xFF0A0D1A),
    textSecondary = Color(0xFF4B5280),
    textMuted     = Color(0xFF9AA0C0),
    divider       = Color(0xFFDDE0F0),
    glassStart    = Color(0xB3FFFFFF),
    glassEnd      = Color(0x66FFFFFF),
    glassStroke   = Color(0xCCFFFFFF),
    blobA         = Color(0x207C3AED),
    blobB         = Color(0x150284C7),
    isDark        = false
)

val LocalAethelColors = staticCompositionLocalOf { DarkPalette }
