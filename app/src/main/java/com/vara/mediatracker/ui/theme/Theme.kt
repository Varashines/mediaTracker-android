package com.vara.mediatracker.ui.theme

import android.os.Build
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
    val gentleDp = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.85f, stiffness = 380f)
    val snappyDp = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.72f, stiffness = 620f)

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
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.1).sp
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 10.5.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * Expressive shape scale.
 *
 * Corners are noticeably rounder than stock Material 3 — the expressive direction —
 * with a large radius reserved for containers that should read as floating surfaces.
 * Asymmetric shapes introduce tactile identity inspired by Material 3 Expressive.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object ExpressiveShapes {
    /** Uniform hero card shape */
    val Hero = RoundedCornerShape(22.dp)
    val AsymHero = RoundedCornerShape(22.dp)

    /** Uniform media / list card shape. Matches ExpressiveCard's default. */
    val Card = RoundedCornerShape(18.dp)
    val AsymCard = RoundedCornerShape(18.dp)

    /** Squircle tag / badge shape */
    val SquircleTag = RoundedCornerShape(10.dp)

    /** Connected pill control container */
    val PillContainer = RoundedCornerShape(24.dp)

    /** Inner active squircle pill */
    val ActivePill = RoundedCornerShape(14.dp)
}

data class ThemePreset(
    val name: String,
    val accentHex: String,
    val darkBGHex: String,
    val darkSurfaceHex: String,
    val primaryColor: Color,
    val onPrimaryColor: Color,
    val primaryContainerColor: Color,
    val onPrimaryContainerColor: Color,
    val backgroundColor: Color,
    val surfaceColor: Color,
    val surfaceContainerHighColor: Color,
    val surfaceContainerHighestColor: Color
)

object ThemePresets {
    val presets = listOf(
        // 0: Mac (Native Azure / Deep Slate)
        ThemePreset(
            name = "Mac",
            accentHex = "#0A84FF",
            darkBGHex = "#1C1D20",
            darkSurfaceHex = "#222428",
            primaryColor = Color(0xFF0A84FF),
            onPrimaryColor = Color.White,
            primaryContainerColor = Color(0xFF004080),
            onPrimaryContainerColor = Color(0xFFD0E4FF),
            backgroundColor = Color(0xFF131417),
            surfaceColor = Color(0xFF1C1D20),
            surfaceContainerHighColor = Color(0xFF24262B),
            surfaceContainerHighestColor = Color(0xFF2D3036)
        ),
        // 1: Trippy (Cyan / Teal)
        ThemePreset(
            name = "Trippy",
            accentHex = "#3E9DB0",
            darkBGHex = "#1A1D1F",
            darkSurfaceHex = "#212426",
            primaryColor = Color(0xFF3E9DB0),
            onPrimaryColor = Color.White,
            primaryContainerColor = Color(0xFF1A4750),
            onPrimaryContainerColor = Color(0xFFBCE8F0),
            backgroundColor = Color(0xFF121516),
            surfaceColor = Color(0xFF1A1D1F),
            surfaceContainerHighColor = Color(0xFF222629),
            surfaceContainerHighestColor = Color(0xFF2B3034)
        ),
        // 2: Chill (Nordic Emerald / Forest Green)
        ThemePreset(
            name = "Chill",
            accentHex = "#67A06B",
            darkBGHex = "#1A1E1B",
            darkSurfaceHex = "#212621",
            primaryColor = Color(0xFF67A06B),
            onPrimaryColor = Color.White,
            primaryContainerColor = Color(0xFF264828),
            onPrimaryContainerColor = Color(0xFFC7EBC8),
            backgroundColor = Color(0xFF121613),
            surfaceColor = Color(0xFF1A1E1B),
            surfaceContainerHighColor = Color(0xFF222723),
            surfaceContainerHighestColor = Color(0xFF2A312B)
        ),
        // 3: Epic (Violet / Royal Purple)
        ThemePreset(
            name = "Epic",
            accentHex = "#8C66A8",
            darkBGHex = "#1B1A21",
            darkSurfaceHex = "#222130",
            primaryColor = Color(0xFF8C66A8),
            onPrimaryColor = Color.White,
            primaryContainerColor = Color(0xFF452D5A),
            onPrimaryContainerColor = Color(0xFFEADBFA),
            backgroundColor = Color(0xFF131218),
            surfaceColor = Color(0xFF1B1A21),
            surfaceContainerHighColor = Color(0xFF24222D),
            surfaceContainerHighestColor = Color(0xFF2D2B39)
        ),
        // 4: Emotional (Rose Pink / Vivid Berry)
        ThemePreset(
            name = "Emotional",
            accentHex = "#D06A93",
            darkBGHex = "#1F1A1D",
            darkSurfaceHex = "#262024",
            primaryColor = Color(0xFFD06A93),
            onPrimaryColor = Color.White,
            primaryContainerColor = Color(0xFF5E273F),
            onPrimaryContainerColor = Color(0xFFFFD9E6),
            backgroundColor = Color(0xFF161214),
            surfaceColor = Color(0xFF1F1A1D),
            surfaceContainerHighColor = Color(0xFF282125),
            surfaceContainerHighestColor = Color(0xFF33292F)
        ),
        // 5: Sunset (Amber / Warm Tangerine)
        ThemePreset(
            name = "Sunset",
            accentHex = "#E07A48",
            darkBGHex = "#1F1B19",
            darkSurfaceHex = "#27221F",
            primaryColor = Color(0xFFE07A48),
            onPrimaryColor = Color.White,
            primaryContainerColor = Color(0xFF663014),
            onPrimaryContainerColor = Color(0xFFFFDBCC),
            backgroundColor = Color(0xFF161311),
            surfaceColor = Color(0xFF1F1B19),
            surfaceContainerHighColor = Color(0xFF29231F),
            surfaceContainerHighestColor = Color(0xFF342C27)
        )
    )

    fun getPreset(index: Int): ThemePreset = presets.getOrElse(index) { presets[0] }
}

fun createColorSchemeForPreset(preset: ThemePreset, isDark: Boolean = true): ColorScheme {
    return if (isDark) {
        darkColorScheme(
            primary = preset.primaryColor,
            onPrimary = preset.onPrimaryColor,
            primaryContainer = preset.primaryContainerColor,
            onPrimaryContainer = preset.onPrimaryContainerColor,
            secondary = preset.primaryColor.copy(alpha = 0.85f),
            onSecondary = Color.White,
            secondaryContainer = preset.primaryContainerColor.copy(alpha = 0.7f),
            onSecondaryContainer = preset.onPrimaryContainerColor,
            tertiary = Color(0xFFFFD60A), // Gold star
            onTertiary = Color.Black,
            tertiaryContainer = Color(0xFF3E2D00),
            onTertiaryContainer = Color(0xFFFFE082),
            background = preset.backgroundColor,
            onBackground = Color(0xFFF2EFF4),
            surface = preset.surfaceColor,
            onSurface = Color(0xFFF2EFF4),
            surfaceVariant = preset.surfaceColor,
            onSurfaceVariant = Color(0xFFA5A0AB),
            surfaceContainer = preset.surfaceColor,
            surfaceContainerHigh = preset.surfaceContainerHighColor,
            surfaceContainerHighest = preset.surfaceContainerHighestColor,
            outline = Color(0xFF484550),
            outlineVariant = Color(0xFF2C2A33),
            error = Color(0xFFFF5252),
            onError = Color.White
        )
    } else {
        // Light mode: near-white surfaces subtly tinted toward the preset accent so
        // the six palettes stay distinguishable instead of all collapsing to grey.
        // Container tokens are opaque — translucent containers composite against
        // whatever sits behind them and make contrast unpredictable.
        val accent = preset.primaryColor
        val ink = Color(0xFF16171C)
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = lerp(Color.White, accent, 0.18f),
            onPrimaryContainer = lerp(ink, accent, 0.35f),
            secondary = lerp(ink, accent, 0.35f),
            onSecondary = Color.White,
            secondaryContainer = lerp(Color.White, accent, 0.12f),
            onSecondaryContainer = lerp(ink, accent, 0.35f),
            tertiary = Color(0xFFB58900),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFF3CD),
            onTertiaryContainer = Color(0xFF4A3700),
            background = lerp(Color.White, accent, 0.04f),
            onBackground = ink,
            surface = lerp(Color.White, accent, 0.02f),
            onSurface = ink,
            surfaceVariant = lerp(Color.White, accent, 0.07f),
            onSurfaceVariant = lerp(ink, accent, 0.22f),
            surfaceContainer = lerp(Color.White, accent, 0.06f),
            surfaceContainerHigh = lerp(Color.White, accent, 0.10f),
            surfaceContainerHighest = lerp(Color.White, accent, 0.15f),
            outline = lerp(Color(0xFF8A8F9C), accent, 0.12f),
            outlineVariant = lerp(Color(0xFFD2D6E0), accent, 0.10f),
            error = Color(0xFFD32F2F),
            onError = Color.White
        )
    }
}

/**
 * Semantic accents that must mean the same thing in either theme (a "loved" badge,
 * a gold star, a success dot). These are brand meanings Material You cannot derive
 * from the wallpaper, so each mode gets a hand-tuned variant with enough contrast on
 * its own background.
 */
data class SemanticColors(
    val loved: Color,
    val liked: Color,
    val disliked: Color,
    val gold: Color,
    val success: Color,
    val warning: Color
)

private val DarkSemanticColors = SemanticColors(
    loved = Color(0xFFFF2D55),
    liked = Color(0xFF34C759),
    disliked = Color(0xFFFF9500),
    gold = Color(0xFFFFD60A),
    success = Color(0xFF6DDFB8),
    warning = Color(0xFFFFB300)
)

private val LightSemanticColors = SemanticColors(
    loved = Color(0xFFD81B47),
    liked = Color(0xFF1B8E3D),
    disliked = Color(0xFFC26A00),
    gold = Color(0xFFA67C00),
    success = Color(0xFF0B7A52),
    warning = Color(0xFFA86200)
)

val LocalSemanticColors = staticCompositionLocalOf { DarkSemanticColors }

val LocalThemePreset = staticCompositionLocalOf { 0 }
val LocalIsDarkTheme = staticCompositionLocalOf { true }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaTrackerTheme(
    presetIndex: Int = 0,
    themeMode: Int = 1, // 0: System, 1: Dark, 2: Light
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        1 -> true
        2 -> false
        else -> isSystemDark
    }

    val preset = ThemePresets.getPreset(presetIndex)
    val colorScheme = createColorSchemeForPreset(preset, isDark = isDark)

    CompositionLocalProvider(
        LocalMotion provides AppMotion,
        LocalThemePreset provides presetIndex,
        LocalIsDarkTheme provides isDark,
        LocalSemanticColors provides if (isDark) DarkSemanticColors else LightSemanticColors
    ) {
        // Material 3 Expressive theme: supplies the expressive motion scheme (springy
        // spatial motion) on top of the app's own colour scheme, shapes and typography.
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}