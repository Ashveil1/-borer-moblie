package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

val BorerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val BorerChatColorScheme = darkColorScheme(
    primary = ChatWhiteAccent,
    onPrimary = ChatCanvasBg,
    primaryContainer = ChatWhiteContainer,
    onPrimaryContainer = ChatOnWhiteContainer,
    secondary = ChatWhiteAccent,
    onSecondary = ChatCanvasBg,
    secondaryContainer = ChatGrayContainer,
    onSecondaryContainer = ChatOnGrayContainer,
    tertiary = ChatGrayAccent,
    onTertiary = ChatCanvasBg,
    tertiaryContainer = ChatGrayContainer,
    onTertiaryContainer = ChatOnGrayContainer,
    error = ChatGrayAccent,
    onError = ChatCanvasBg,
    errorContainer = ChatGrayContainer,
    onErrorContainer = ChatOnGrayContainer,
    background = ChatCanvasBg,
    onBackground = ChatInk,
    surface = ChatSurface,
    onSurface = ChatInk,
    surfaceVariant = ChatSurfaceRaised,
    onSurfaceVariant = ChatInkSecondary,
    surfaceContainerLowest = ChatCanvasBg,
    surfaceContainerLow = ChatSidebarBg,
    surfaceContainer = ChatSurface,
    surfaceContainerHigh = ChatSurfaceRaised,
    surfaceContainerHighest = ChatUserBubble,
    outline = ChatHairline,
    outlineVariant = ChatHairlineStrong
)

@Composable
fun BorerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BorerChatColorScheme,
        typography = BorerTypography,
        shapes = BorerShapes,
        content = content
    )
}
