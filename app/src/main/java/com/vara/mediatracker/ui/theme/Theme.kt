package com.vara.mediatracker.ui.theme

import android.os.Build
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.R

/**
 * Brand palette.
 *
 * Retained as the fallback and as the source for semantic accents that Material You
 * cannot express — a "loved" badge needs to mean the same thing regardless of the
 * user's wallpaper.
 */
object AppColors {
    val Background = Color(0xFF0F1014)       // OLED near-black
    val Surface = Color(0xFF16181F)          // Deep glass tone
    val SurfaceVariant = Color(0xFF20232D)   // Elevated container
    val SurfaceBright = Color(0xFF2A2D3A)    // Interactive borders/highlights
    val Accent = Color(0xFF0A84FF)           // macOS / iOS Vivid Azure
    val AccentLuminous = Color(0xFF5AC8FA)   // Cyan bloom
    val Loved = Color(0xFFFF2D55)            // Vivid Apple Pink/Red
    val Liked = Color(0xFF34C759)            // Forest Green
    val Disliked = Color(0xFFFF9500)         // Vivid Amber
    val Gold = Color(0xFFFFD60A)             // TMDB / IMDb Star Gold
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF9898A0)
    val TextTertiary = Color(0xFF636366)
    val Stroke = Color(0xFF2E3240)
}

// ---------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------

/**
 * Motion tokens.
 *
 * The app previously had exactly one animation in its whole codebase, so durations and
 * easings were invented per call site. Centralising them is what makes transitions
 * feel like one system instead of a pile of unrelated effects.
 *
 * Durations stay short on purpose: this is a dense media browser, and anything past
 * ~400ms reads as lag when flicking through rails.
 */
object AppMotion {
    /** Opacity/colour shifts. */
    const val FAST = 150

    /** Default for most transitions. */
    const val STANDARD = 250

    /** Entrances and larger layout changes. */
    const val EMPHASIZED = 400

    /** Distance travelled by slide/directional transitions. */
    val slideDistance = 32.dp

    // Springs mirror the macOS app's springGentle / springSnappy vocabulary.
    val gentle = spring<Float>(dampingRatio = 0.85f, stiffness = 380f)
    val snappy = spring<Float>(dampingRatio = 0.72f, stiffness = 620f)

    val standardEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedEase = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exitEase = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** Exposed so screens can read motion tokens without importing the theme object. */
val LocalMotion = staticCompositionLocalOf { AppMotion }

// ---------------------------------------------------------------------------
// Typography
// ---------------------------------------------------------------------------

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val PlusJakartaFont = GoogleFont("Plus Jakarta Sans")

/**
 * Downloadable fonts are unavailable until the provider has been warmed up, which
 * makes a cold first frame fall back. Declaring the family with a matching system
 * fallback keeps the first paint correct instead of snapping once the font arrives.
 */
val AppFontFamily = FontFamily(
    Font(googleFont = PlusJakartaFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = PlusJakartaFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = PlusJakartaFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = PlusJakartaFont, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = PlusJakartaFont, fontProvider = provider, weight = FontWeight.ExtraBold)
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
        color = AppColors.TextPrimary
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
        color = AppColors.TextPrimary
    ),
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
        color = AppColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
        color = AppColors.TextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.1).sp,
        color = AppColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
        color = AppColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
        color = AppColors.TextPrimary
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = AppColors.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = AppColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        color = AppColors.TextSecondary
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = AppColors.TextTertiary
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
        color = AppColors.TextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
        color = AppColors.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        letterSpacing = 0.4.sp,
        color = AppColors.TextTertiary
    )
)

/**
 * Expressive shape scale.
 *
 * Corners are noticeably rounder than stock Material 3 — the expressive direction —
 * with a large radius reserved for containers that should read as floating surfaces.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// ---------------------------------------------------------------------------
// Color schemes
// ---------------------------------------------------------------------------

private val CinematicDark = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color.White,
    primaryContainer = AppColors.Accent.copy(alpha = 0.22f),
    onPrimaryContainer = AppColors.AccentLuminous,
    secondary = AppColors.SurfaceBright,
    onSecondary = AppColors.TextPrimary,
    secondaryContainer = AppColors.SurfaceVariant,
    onSecondaryContainer = AppColors.TextPrimary,
    tertiary = AppColors.Gold,
    onTertiary = Color.Black,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceVariant,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceVariant,
    surfaceContainerHighest = AppColors.SurfaceBright,
    outline = AppColors.Stroke,
    outlineVariant = AppColors.Stroke.copy(alpha = 0.6f),
    error = Color(0xFFFF453A),
    onError = Color.White
)

/** Light counterpart, previously missing — the app was dark-only with no way out. */
private val CinematicLight = lightColorScheme(
    primary = Color(0xFF0066CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF545F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color.White,
    background = Color(0xFFF9F9FB),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2E8),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainer = Color(0xFFF1F2F6),
    surfaceContainerHigh = Color(0xFFEBECF0),
    surfaceContainerHighest = Color(0xFFE4E5EA),
    outline = Color(0xFFC3C6CF),
    outlineVariant = Color(0xFFD7D9E0),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

@Composable
fun MediaTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) CinematicDark else CinematicLight

    CompositionLocalProvider(LocalMotion provides AppMotion) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}