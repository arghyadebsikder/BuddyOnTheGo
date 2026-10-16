package com.arghya.buddyonthego.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.K

/** Extra design tokens that Material's ColorScheme does not carry. */
data class Look(
    val dark: Boolean,
    val bgTop: Color, val bgBottom: Color,
    val glowA: Color, val glowB: Color,
    val cardTop: Color, val cardBottom: Color,
    val borderHi: Color, val borderLo: Color,
    val navBg: Color,
    val accentA: Color, val accentB: Color,
)

private val DarkLook = Look(
    dark = true,
    bgTop = Color(0xFF070916), bgBottom = Color(0xFF0E1130),
    glowA = Color(0x3322D3EE), glowB = Color(0x338B5CF6),
    cardTop = Color(0x16FFFFFF), cardBottom = Color(0x08FFFFFF),
    borderHi = Color(0x38FFFFFF), borderLo = Color(0x0DFFFFFF),
    navBg = Color(0xF2151836),
    accentA = Color(0xFF5CE1E6), accentB = Color(0xFF8E7BFF),
)

private val LightLook = Look(
    dark = false,
    bgTop = Color(0xFFF7F8FF), bgBottom = Color(0xFFE8ECFF),
    glowA = Color(0x2A5CE1E6), glowB = Color(0x258E7BFF),
    cardTop = Color(0xEBFFFFFF), cardBottom = Color(0xB3FFFFFF),
    borderHi = Color(0xFFFFFFFF), borderLo = Color(0x203B5BDB),
    navBg = Color(0xF5FFFFFF),
    accentA = Color(0xFF0EA5C6), accentB = Color(0xFF5B45E0),
)

val LocalLook = staticCompositionLocalOf { DarkLook }

private val Amber = Color(0xFFFFC23D)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF), onPrimaryContainer = Color(0xFF0E1A5C),
    secondary = Color(0xFF6C47E8), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E0FF), onSecondaryContainer = Color(0xFF21005D),
    tertiary = Color(0xFFE09B00), onTertiary = Color(0xFF241500),
    background = Color(0xFFF7F8FF), surface = Color.White,
    surfaceVariant = Color(0xFFE6E9FA), onSurface = Color(0xFF12142B), onSurfaceVariant = Color(0xFF5A6080),
    error = Color(0xFFD93A55), errorContainer = Color(0xFFFFDADF), onErrorContainer = Color(0xFF410010),
    outline = Color(0xFFB6BBD8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC), onPrimary = Color(0xFF00283B),
    primaryContainer = Color(0xFF1B2A63), onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFA78BFA), onSecondary = Color(0xFF260B6B),
    secondaryContainer = Color(0xFF3A2A7A), onSecondaryContainer = Color(0xFFE9E0FF),
    tertiary = Amber, onTertiary = Color(0xFF241500),
    background = Color(0xFF070916), surface = Color(0xFF111430),
    surfaceVariant = Color(0xFF232748), onSurface = Color(0xFFE8EAFB), onSurfaceVariant = Color(0xFF9AA0C4),
    error = Color(0xFFFF6B81), errorContainer = Color(0xFF4A1623), onErrorContainer = Color(0xFFFFDADF),
    outline = Color(0xFF3A3F66),
)

private val BuddyTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.8).sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val BuddyShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val ClockStyle = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-1.5).sp)

@Composable
fun BuddyTheme(content: @Composable () -> Unit) {
    val s = ServiceLocator.settings
    s.tick.collectAsState().value
    val dark = when (s.int(K.THEME, 0)) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val scheme = if (s.bool(K.DYNAMIC, false) && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalLook provides (if (dark) DarkLook else LightLook)) {
        MaterialTheme(colorScheme = scheme, typography = BuddyTypography, shapes = BuddyShapes, content = content)
    }
}
