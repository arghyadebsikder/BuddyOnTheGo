package com.arghya.buddyonthego.ui

import android.graphics.Color as AColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.domain.ThemeSchedule
import kotlinx.coroutines.delay
import java.util.Calendar

/** Extra design tokens that Material's ColorScheme does not carry. */
data class Look(
    val dark: Boolean,
    val bgTop: Color, val bgBottom: Color,
    val glowA: Color, val glowB: Color,
    val cardTop: Color, val cardBottom: Color,
    val borderHi: Color, val borderLo: Color,
    val navBg: Color,
    val accentA: Color, val accentB: Color,
    /** Gradient of the big primary buttons and the text colour on top of it. */
    val btnA: Color, val btnB: Color, val onBtn: Color,
    val success: Color, val warning: Color, val danger: Color,
)

// Premium look: deep midnight with champagne gold and soft amethyst (dark), warm ivory with ink indigo and gold (light).
private val DarkLook = Look(
    dark = true,
    bgTop = Color(0xFF0A0B14), bgBottom = Color(0xFF14152B),
    glowA = Color(0x33F2C96B), glowB = Color(0x3A8F7BFF),
    cardTop = Color(0x1AFFFFFF), cardBottom = Color(0x0AFFFFFF),
    borderHi = Color(0x40FFFFFF), borderLo = Color(0x10FFFFFF),
    navBg = Color(0xF2161830),
    accentA = Color(0xFFF6D88F), accentB = Color(0xFFA48CFF),
    btnA = Color(0xFFF8DC96), btnB = Color(0xFFE5A93D), onBtn = Color(0xFF241800),
    success = Color(0xFF4FD6A5), warning = Color(0xFFF2C96B), danger = Color(0xFFFF7088),
)

private val LightLook = Look(
    dark = false,
    bgTop = Color(0xFFFAF8F2), bgBottom = Color(0xFFEDEAF8),
    glowA = Color(0x33E5B34A), glowB = Color(0x286A5BFF),
    cardTop = Color(0xF2FFFFFF), cardBottom = Color(0xCCFFFFFF),
    borderHi = Color(0xFFFFFFFF), borderLo = Color(0x2A4A3FC9),
    navBg = Color(0xF8FFFFFF),
    accentA = Color(0xFFC8962B), accentB = Color(0xFF5B4CF0),
    btnA = Color(0xFF6A5CFA), btnB = Color(0xFF3D34C6), onBtn = Color.White,
    success = Color(0xFF1E9E72), warning = Color(0xFFC8962B), danger = Color(0xFFD13B58),
)

val LocalLook = staticCompositionLocalOf { DarkLook }

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D34C6), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E0FF), onPrimaryContainer = Color(0xFF14104F),
    secondary = Color(0xFF9A7418), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBEBC4), onSecondaryContainer = Color(0xFF3A2900),
    tertiary = Color(0xFFC8962B), onTertiary = Color(0xFF2A1B00),
    background = Color(0xFFFAF8F2), surface = Color.White, onBackground = Color(0xFF14152B),
    surfaceVariant = Color(0xFFEAE7F6), onSurface = Color(0xFF14152B), onSurfaceVariant = Color(0xFF585C7B),
    error = Color(0xFFD13B58), errorContainer = Color(0xFFFFDADF), onErrorContainer = Color(0xFF410010),
    outline = Color(0xFFB9B6D4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF6D88F), onPrimary = Color(0xFF241800),
    primaryContainer = Color(0xFF3B2F12), onPrimaryContainer = Color(0xFFFFEFC7),
    secondary = Color(0xFFB9A6FF), onSecondary = Color(0xFF241466),
    secondaryContainer = Color(0xFF392C78), onSecondaryContainer = Color(0xFFE9E0FF),
    tertiary = Color(0xFF4FD6A5), onTertiary = Color(0xFF00382A),
    background = Color(0xFF0A0B14), surface = Color(0xFF15172C), onBackground = Color(0xFFF1F0F8),
    surfaceVariant = Color(0xFF22254A), onSurface = Color(0xFFF1F0F8), onSurfaceVariant = Color(0xFFA6A9C6),
    error = Color(0xFFFF7088), errorContainer = Color(0xFF4A1623), onErrorContainer = Color(0xFFFFDADF),
    outline = Color(0xFF3C4070),
)

// Bigger than the Material defaults so the app reads easily at arm's length.
private val BuddyTypography = Typography(
    displaySmall = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.8).sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val BuddyShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val ClockStyle = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-1.5).sp)

private fun minuteOfDay(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

/** True when the app should be dark right now, for the chosen theme mode (0 device, 1 light, 2 dark, 3 auto by time). */
@Composable
fun rememberIsDark(): Boolean {
    val s = ServiceLocator.settings
    s.tick.collectAsState().value
    val mode = s.int(K.THEME, 0)
    val system = isSystemInDarkTheme()
    // Re-check twice a minute so the switch happens on time, without any restart.
    val minute by produceState(minuteOfDay(), mode) {
        while (true) { value = minuteOfDay(); delay(30_000) }
    }
    return when (mode) {
        1 -> false
        2 -> true
        3 -> ThemeSchedule.isDark(minute, s.int(K.DAY_START, ThemeSchedule.DEFAULT_DAY), s.int(K.NIGHT_START, ThemeSchedule.DEFAULT_NIGHT))
        else -> system
    }
}

@Composable
fun BuddyTheme(content: @Composable () -> Unit) {
    val s = ServiceLocator.settings
    s.tick.collectAsState().value
    val dark = rememberIsDark()
    val ctx = LocalContext.current

    // Keep the status and navigation bar icons readable when the app theme differs from the phone theme.
    val activity = ctx as? ComponentActivity
    DisposableEffect(dark, activity) {
        val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }

    val scheme = if (s.bool(K.DYNAMIC, false) && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalLook provides (if (dark) DarkLook else LightLook)) {
        MaterialTheme(colorScheme = scheme, typography = BuddyTypography, shapes = BuddyShapes, content = content)
    }
}
