package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BlinkDarkColorScheme = darkColorScheme(
    primary = OpticalCyan,
    onPrimary = AmoledBlack,
    primaryContainer = OpticalCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = LockMint,
    onSecondary = AmoledBlack,
    secondaryContainer = LockMintContainer,
    onSecondaryContainer = LockMint,
    tertiary = VerifiedAmber,
    onTertiary = AmoledBlack,
    tertiaryContainer = VerifiedAmberContainer,
    onTertiaryContainer = VerifiedAmber,
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = ObsidianElevated,
    outline = SubtleBorder,
    error = AlertCoral,
    onError = Color.White,
    errorContainer = AlertCoralContainer,
    onErrorContainer = AlertCoral
)

@Composable
fun BlinkAITheme(
    isBlackScreenMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (isBlackScreenMode) {
        BlinkDarkColorScheme.copy(
            background = AmoledBlack,
            surface = AmoledBlack,
            surfaceVariant = Color(0xFF050505)
        )
    } else {
        BlinkDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
