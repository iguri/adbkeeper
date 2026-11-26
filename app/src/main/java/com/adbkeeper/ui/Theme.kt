package com.adbkeeper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E7C66),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF10151C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10151C),
    surfaceVariant = Color(0xFFEDF0F4),
    onSurfaceVariant = Color(0xFF6B7580),
    outline = Color(0xFFDCE1E8),
    error = Color(0xFFC2410C),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF06281F),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE9EEF5),
    surface = Color(0xFF151B23),
    onSurface = Color(0xFFE9EEF5),
    surfaceVariant = Color(0xFF1E252F),
    onSurfaceVariant = Color(0xFF93A0AE),
    outline = Color(0xFF2A3340),
    error = Color(0xFFF59E0B),
)

/** 与主题色板互补的语义色，避免在深浅色之间反复硬编码。 */
data class KeeperColors(
    val hero: Color,
    val heroSub: Color,
    val ok: Color,
    val okBg: Color,
    val bad: Color,
    val badBg: Color,
    val codeBg: Color,
    val hairline: Color,
)

private val LightKeeperColors = KeeperColors(
    hero = Color(0xFF10151C),
    heroSub = Color(0x99FFFFFF),
    ok = Color(0xFF12855C),
    okBg = Color(0x1412855C),
    bad = Color(0xFFC2410C),
    badBg = Color(0x14C2410C),
    codeBg = Color(0xFFF4F6F9),
    hairline = Color(0xFFE8EBF0),
)

private val DarkKeeperColors = KeeperColors(
    hero = Color(0xFF06090D),
    heroSub = Color(0x8CFFFFFF),
    ok = Color(0xFF34D399),
    okBg = Color(0x1F34D399),
    bad = Color(0xFFF87171),
    badBg = Color(0x1FF87171),
    codeBg = Color(0xFF0F141B),
    hairline = Color(0xFF232B36),
)

val LocalKeeper = staticCompositionLocalOf { LightKeeperColors }

@Composable
fun KeeperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalKeeper provides if (dark) DarkKeeperColors else LightKeeperColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            content = content,
        )
    }
}
