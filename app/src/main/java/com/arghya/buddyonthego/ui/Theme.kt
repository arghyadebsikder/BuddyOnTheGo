package com.arghya.buddyonthego.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.K

private val Amber = Color(0xFFFFB300)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F43A8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E1FF), onPrimaryContainer = Color(0xFF0E1062),
    secondary = Color(0xFF8A5A00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA6), onSecondaryContainer = Color(0xFF2B1A00),
    tertiary = Amber, onTertiary = Color(0xFF241500),
    background = Color(0xFFFBF9FF), surface = Color(0xFFFBF9FF),
    surfaceVariant = Color(0xFFE5E3F3), onSurface = Color(0xFF1B1B21),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC2FF), onPrimary = Color(0xFF14186E),
    primaryContainer = Color(0xFF2B2E7A), onPrimaryContainer = Color(0xFFE2E1FF),
    secondary = Color(0xFFFFB955), onSecondary = Color(0xFF462A00),
    secondaryContainer = Color(0xFF654000), onSecondaryContainer = Color(0xFFFFDEA6),
    tertiary = Amber, onTertiary = Color(0xFF241500),
    background = Color(0xFF0F1020), surface = Color(0xFF14152A),
    surfaceVariant = Color(0xFF2A2B45), onSurface = Color(0xFFE4E1EC),
)

val ClockStyle = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Light)

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
    MaterialTheme(colorScheme = scheme, content = content)
}
