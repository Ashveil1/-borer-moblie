package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BorerChatColorScheme = darkColorScheme(
    primary = ChatWhiteAccent,
    onPrimary = ChatCanvasBg,
    primaryContainer = ChatWhiteContainer,
    onPrimaryContainer = ChatOnWhiteContainer,
    secondary = ChatEmerald,
    onSecondary = ChatCanvasBg,
    secondaryContainer = ChatEmeraldContainer,
    onSecondaryContainer = ChatOnEmeraldContainer,
    tertiary = ChatAmber,
    onTertiary = ChatCanvasBg,
    tertiaryContainer = ChatAmberContainer,
    onTertiaryContainer = ChatOnAmberContainer,
    error = ChatRed,
    onError = ChatInk,
    errorContainer = ChatRedContainer,
    onErrorContainer = ChatOnRedContainer,
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
    outlineVariant = ChatHairline
)

@Composable
fun BorerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BorerChatColorScheme,
        typography = BorerTypography,
        content = content
    )
}
