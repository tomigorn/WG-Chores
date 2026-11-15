package ch.tomigorn.wgchores.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The web app's tokens (web/src/app.css): neutral surfaces, one accent, due colours. */
@Immutable
data class WgcColors(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentText: Color,
    val dangerBg: Color,
    val dangerBorder: Color,
    val dangerText: Color,
    val due: Map<String, Pair<Color, Color>>,
)

val LightColors = WgcColors(
    bg = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE3E3E6),
    borderStrong = Color(0xFF8A8B92),
    text = Color(0xFF18181B),
    muted = Color(0xFF5D5E66),
    accent = Color(0xFF2A55C5),
    accentText = Color(0xFFFFFFFF),
    dangerBg = Color(0xFFFDECEA),
    dangerBorder = Color(0xFFF3C4BF),
    dangerText = Color(0xFF9B1C13),
    due = mapOf(
        "fresh" to (Color(0xFFE1F3E6) to Color(0xFF17612F)),
        "soon" to (Color(0xFFF8F0C4) to Color(0xFF6B5200)),
        "close" to (Color(0xFFFDE3C8) to Color(0xFF8F4300)),
        "due" to (Color(0xFFFBDCD9) to Color(0xFFA8221A)),
        "overdue" to (Color(0xFFD4382C) to Color.White),
        "long_overdue" to (Color(0xFFA8211A) to Color.White),
        "never" to (Color(0xFF73150F) to Color.White),
    ),
)

val DarkColors = WgcColors(
    bg = Color(0xFF111214),
    surface = Color(0xFF18191C),
    border = Color(0xFF2B2D31),
    borderStrong = Color(0xFF6A6C73),
    text = Color(0xFFECECEF),
    muted = Color(0xFFA2A4AB),
    accent = Color(0xFF8EAAFF),
    accentText = Color(0xFF0C1633),
    dangerBg = Color(0xFF3A1714),
    dangerBorder = Color(0xFF5E2621),
    dangerText = Color(0xFFFFB4AB),
    due = mapOf(
        "fresh" to (Color(0xFF16331F) to Color(0xFF86D69E)),
        "soon" to (Color(0xFF362F0D) to Color(0xFFE6CF63)),
        "close" to (Color(0xFF3F270F) to Color(0xFFF4A660)),
        "due" to (Color(0xFF46191A) to Color(0xFFFF9A8F)),
        "overdue" to (Color(0xFFB3261E) to Color.White),
        "long_overdue" to (Color(0xFFD93A2B) to Color.White),
        "never" to (Color(0xFFFF6B5A) to Color(0xFF2A0602)),
    ),
)

val LocalWgcColors = staticCompositionLocalOf { LightColors }

object Wgc {
    val colors: WgcColors @Composable get() = LocalWgcColors.current
}

private fun base(size: Int, weight: FontWeight = FontWeight.Normal, line: Double = 1.45) =
    TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp, letterSpacing = 0.sp)

private val typography = Typography(
    headlineSmall = base(24, FontWeight.Bold, 1.25).copy(letterSpacing = (-0.01).em),
    titleLarge = base(20, FontWeight.SemiBold, 1.3),
    titleMedium = base(16, FontWeight.SemiBold),
    bodyLarge = base(16),
    bodyMedium = base(14),
    bodySmall = base(13),
    labelLarge = base(16, FontWeight.SemiBold, 1.25),
    labelMedium = base(14, FontWeight.Medium),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
)

@Composable
fun WgcTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent,
        onPrimary = c.accentText,
        secondary = c.accent,
        onSecondary = c.accentText,
        background = c.bg,
        onBackground = c.text,
        surface = c.bg,
        onSurface = c.text,
        surfaceVariant = c.surface,
        onSurfaceVariant = c.muted,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surface,
        secondaryContainer = c.border,
        onSecondaryContainer = c.text,
        outline = c.borderStrong,
        outlineVariant = c.border,
        error = c.dangerText,
        errorContainer = c.dangerBg,
        onErrorContainer = c.dangerText,
    )
    CompositionLocalProvider(LocalWgcColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
