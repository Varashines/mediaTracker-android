package com.vara.mediatracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppColors {
    // Deep Cinematic Dark Palette
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

val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
        color = AppColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.2).sp,
        color = AppColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        color = AppColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        color = AppColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        color = AppColors.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        letterSpacing = 0.5.sp,
        color = AppColors.TextTertiary
    )
)

val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun MediaTrackerTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = AppColors.Accent,
        onPrimary = Color.White,
        primaryContainer = AppColors.Accent.copy(alpha = 0.2f),
        onPrimaryContainer = AppColors.AccentLuminous,
        secondary = AppColors.SurfaceBright,
        onSecondary = AppColors.TextPrimary,
        background = AppColors.Background,
        onBackground = AppColors.TextPrimary,
        surface = AppColors.Surface,
        onSurface = AppColors.TextPrimary,
        surfaceVariant = AppColors.SurfaceVariant,
        onSurfaceVariant = AppColors.TextSecondary,
        outline = AppColors.Stroke
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
