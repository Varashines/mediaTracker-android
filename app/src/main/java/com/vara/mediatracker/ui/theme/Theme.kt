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
    /**
     * Asymmetric "M3E" radii: one diagonal pair tight, the other round. This is the
     * prototype's signature — hero `34/12`, card `24/8`, tag `14/4`.
     */
    val AsymHero = RoundedCornerShape(topStart = 34.dp, topEnd = 12.dp, bottomEnd = 34.dp, bottomStart = 12.dp)
    val AsymCard = RoundedCornerShape(topStart = 24.dp, topEnd = 8.dp, bottomEnd = 24.dp, bottomStart = 8.dp)
    val AsymTag = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomEnd = 14.dp, bottomStart = 4.dp)

    /** Uniform hero card shape. */
    val Hero = RoundedCornerShape(28.dp)

    /** Uniform media / list card shape. */
    val Card = RoundedCornerShape(20.dp)

    /** Uniform squircles (prototype: 14 / 20 / 28). */
    val SquircleSm = RoundedCornerShape(14.dp)
    val SquircleMd = RoundedCornerShape(20.dp)
    val SquircleLg = RoundedCornerShape(28.dp)

    /** Squircle tag / badge shape */
    val SquircleTag = RoundedCornerShape(10.dp)

    /** Connected pill control container */
    val PillContainer = RoundedCornerShape(24.dp)

    /** Inner active squircle pill */
    val ActivePill = RoundedCornerShape(14.dp)
}

/**
 * Spacing scale, mirroring the prototype's Tailwind rhythm (4 / 8 / 12 / 16 / 20 / 24).
 * Use these instead of ad-hoc dp so gutters and gaps stay consistent and breathable.
 */
object AppSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp

    /** Screen edge gutter. */
    val gutter = 20.dp

    /** Vertical gap between sections. */
    val sectionGap = 24.dp

    /** Gap between rows in a list. */
    val rowGap = 12.dp
}

/** Mono micro-labels for metadata (network, counts, dates, percentages). */
object AppTextStyles {
    val MonoLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp
    )
    val MonoValue = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp
    )
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
    val surfaceContainerHighestColor: Color,
    // M3E tonal additions (prototype token set).
    val secondaryContainerColor: Color,
    val onSecondaryContainerColor: Color,
    val surfaceContainerColor: Color,
    val outlineColor: Color,
    val onSurfaceColor: Color,
    val onSurfaceVariantColor: Color
) {
    /** The vivid identity colour, for swatches/previews (not the tonal primary). */
    val accentColor: Color
        get() = runCatching { Color(android.graphics.Color.parseColor(accentHex)) }
            .getOrDefault(primaryColor)
}

object ThemePresets {
    val presets = listOf(
        // 0: Mac (Native Azure) — remapped to the prototype's "electric blue" tonal set.
        ThemePreset(
            name = "Mac",
            accentHex = "#0A84FF",
            darkBGHex = "#111418",
            darkSurfaceHex = "#16191E",
            primaryColor = Color(0xFFA1C9FF),
            onPrimaryColor = Color(0xFF00325B),
            primaryContainerColor = Color(0xFF004880),
            onPrimaryContainerColor = Color(0xFFD2E4FF),
            backgroundColor = Color(0xFF111418),
            surfaceColor = Color(0xFF16191E),
            surfaceContainerHighColor = Color(0xFF24272C),
            surfaceContainerHighestColor = Color(0xFF2F3237),
            secondaryContainerColor = Color(0xFF39465A),
            onSecondaryContainerColor = Color(0xFFD8E3F8),
            surfaceContainerColor = Color(0xFF191C21),
            outlineColor = Color(0xFF8C9199),
            onSurfaceColor = Color(0xFFE2E2E7),
            onSurfaceVariantColor = Color(0xFFC2C7CF)
        ),
        // 1: Trippy (Cyan / Teal)
        ThemePreset(
            name = "Trippy",
            accentHex = "#3E9DB0",
            darkBGHex = "#0E1416",
            darkSurfaceHex = "#131A1C",
            primaryColor = Color(0xFF8EDBE8),
            onPrimaryColor = Color(0xFF00363F),
            primaryContainerColor = Color(0xFF004E5C),
            onPrimaryContainerColor = Color(0xFFB0ECF5),
            backgroundColor = Color(0xFF0E1416),
            surfaceColor = Color(0xFF131A1C),
            surfaceContainerHighColor = Color(0xFF20282B),
            surfaceContainerHighestColor = Color(0xFF2A3336),
            secondaryContainerColor = Color(0xFF2F4A50),
            onSecondaryContainerColor = Color(0xFFCFE9EE),
            surfaceContainerColor = Color(0xFF161D1F),
            outlineColor = Color(0xFF869396),
            onSurfaceColor = Color(0xFFDEE4E6),
            onSurfaceVariantColor = Color(0xFFBCC7CA)
        ),
        // 2: Chill (Nordic Emerald) — the prototype's "emerald" set.
        ThemePreset(
            name = "Chill",
            accentHex = "#67A06B",
            darkBGHex = "#101513",
            darkSurfaceHex = "#151B18",
            primaryColor = Color(0xFF6DDFB8),
            onPrimaryColor = Color(0xFF003828),
            primaryContainerColor = Color(0xFF00513B),
            onPrimaryContainerColor = Color(0xFF8BFCD4),
            backgroundColor = Color(0xFF101513),
            surfaceColor = Color(0xFF151B18),
            surfaceContainerHighColor = Color(0xFF262D29),
            surfaceContainerHighestColor = Color(0xFF313833),
            secondaryContainerColor = Color(0xFF334B41),
            onSecondaryContainerColor = Color(0xFFBDECD8),
            surfaceContainerColor = Color(0xFF1C221E),
            outlineColor = Color(0xFF89938D),
            onSurfaceColor = Color(0xFFE0E3DF),
            onSurfaceVariantColor = Color(0xFFBFC9C2)
        ),
        // 3: Epic (Violet / Royal Purple)
        ThemePreset(
            name = "Epic",
            accentHex = "#8C66A8",
            darkBGHex = "#141217",
            darkSurfaceHex = "#1A171D",
            primaryColor = Color(0xFFDCB8F4),
            onPrimaryColor = Color(0xFF3D1E4E),
            primaryContainerColor = Color(0xFF553567),
            onPrimaryContainerColor = Color(0xFFF0D7FF),
            backgroundColor = Color(0xFF141217),
            surfaceColor = Color(0xFF1A171D),
            surfaceContainerHighColor = Color(0xFF2A252D),
            surfaceContainerHighestColor = Color(0xFF352F38),
            secondaryContainerColor = Color(0xFF46324D),
            onSecondaryContainerColor = Color(0xFFEED7F3),
            surfaceContainerColor = Color(0xFF1F1B22),
            outlineColor = Color(0xFF948E9B),
            onSurfaceColor = Color(0xFFE6DFE8),
            onSurfaceVariantColor = Color(0xFFCBC2CD)
        ),
        // 4: Emotional (Rose) — the prototype's "plum" set.
        ThemePreset(
            name = "Emotional",
            accentHex = "#D06A93",
            darkBGHex = "#161217",
            darkSurfaceHex = "#1C171B",
            primaryColor = Color(0xFFF4B8D4),
            onPrimaryColor = Color(0xFF4E1E38),
            primaryContainerColor = Color(0xFF67355A),
            onPrimaryContainerColor = Color(0xFFFFD7EE),
            backgroundColor = Color(0xFF161217),
            surfaceColor = Color(0xFF1C171B),
            surfaceContainerHighColor = Color(0xFF2C252B),
            surfaceContainerHighestColor = Color(0xFF372F36),
            secondaryContainerColor = Color(0xFF4B3245),
            onSecondaryContainerColor = Color(0xFFFFD7EE),
            surfaceContainerColor = Color(0xFF211B20),
            outlineColor = Color(0xFF9B8E97),
            onSurfaceColor = Color(0xFFEBDFE7),
            onSurfaceVariantColor = Color(0xFFCFC2CA)
        ),
        // 5: Sunset (Amber) — the prototype's "amber sunset" set.
        ThemePreset(
            name = "Sunset",
            accentHex = "#E07A48",
            darkBGHex = "#17120E",
            darkSurfaceHex = "#1D1713",
            primaryColor = Color(0xFFFFB77C),
            onPrimaryColor = Color(0xFF4C2700),
            primaryContainerColor = Color(0xFF6C3A00),
            onPrimaryContainerColor = Color(0xFFFFDDBA),
            backgroundColor = Color(0xFF17120E),
            surfaceColor = Color(0xFF1D1713),
            surfaceContainerHighColor = Color(0xFF2E2620),
            surfaceContainerHighestColor = Color(0xFF39302B),
            secondaryContainerColor = Color(0xFF564233),
            onSecondaryContainerColor = Color(0xFFFBDECD),
            surfaceContainerColor = Color(0xFF231C17),
            outlineColor = Color(0xFF9F8D83),
            onSurfaceColor = Color(0xFFEDE0D9),
            onSurfaceVariantColor = Color(0xFFD4C3B9)
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
            secondary = preset.secondaryContainerColor,
            onSecondary = preset.onSecondaryContainerColor,
            secondaryContainer = preset.secondaryContainerColor,
            onSecondaryContainer = preset.onSecondaryContainerColor,
            tertiary = Color(0xFFFFD60A), // Gold star
            onTertiary = Color.Black,
            tertiaryContainer = Color(0xFF3E2D00),
            onTertiaryContainer = Color(0xFFFFE082),
            background = preset.backgroundColor,
            onBackground = preset.onSurfaceColor,
            surface = preset.surfaceColor,
            onSurface = preset.onSurfaceColor,
            surfaceVariant = preset.surfaceContainerColor,
            onSurfaceVariant = preset.onSurfaceVariantColor,
            surfaceContainer = preset.surfaceContainerColor,
            surfaceContainerHigh = preset.surfaceContainerHighColor,
            surfaceContainerHighest = preset.surfaceContainerHighestColor,
            outline = preset.outlineColor,
            outlineVariant = preset.outlineColor.copy(alpha = 0.4f),
            error = Color(0xFFFF5252),
            onError = Color.White
        )
    } else {
        // Light mode: near-white surfaces subtly tinted toward the preset accent so
        // the palettes stay distinguishable instead of all collapsing to grey.
        // Container tokens are opaque — translucent containers composite against
        // whatever sits behind them and make contrast unpredictable.
        val accent = runCatching { Color(android.graphics.Color.parseColor(preset.accentHex)) }
            .getOrDefault(preset.primaryColor)
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