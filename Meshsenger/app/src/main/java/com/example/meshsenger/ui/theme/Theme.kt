package com.example.meshsenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DarkLeafPrimary,
    onPrimary = Color(0xFF06350C),
    primaryContainer = Color(0xFF1D5A24),
    onPrimaryContainer = Color(0xFFD6FFD1),
    secondary = DarkMintSecondary,
    secondaryContainer = Color(0xFF31543D),
    tertiary = DarkLimeTertiary,
    tertiaryContainer = Color(0xFF4D6412),
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    outline = Color(0xFF91A58F),
)

private val LightColorScheme = lightColorScheme(
    primary = LeafPrimary,
    onPrimary = LeafOnPrimary,
    primaryContainer = LeafPrimaryContainer,
    onPrimaryContainer = LeafOnPrimaryContainer,
    secondary = MintSecondary,
    onSecondary = MintOnSecondary,
    secondaryContainer = MintSecondaryContainer,
    onSecondaryContainer = MintOnSecondaryContainer,
    tertiary = LimeTertiary,
    tertiaryContainer = LimeContainer,
    background = NatureBackground,
    surface = NatureSurface,
    surfaceVariant = NatureSurfaceVariant,
    outline = NatureOutline,
)

@Composable
fun MeshsengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
