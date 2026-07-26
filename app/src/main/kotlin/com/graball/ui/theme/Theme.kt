package com.graball.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// tokens from design/mockups.html (oklch converted to sRGB)
private val DarkColors = darkColorScheme(
    background = Color(0xFF050505),
    onBackground = Color(0xFFEAE7E6),
    surface = Color(0xFF100E0D),
    onSurface = Color(0xFFEAE7E6),
    surfaceContainer = Color(0xFF1A1817),
    surfaceContainerHigh = Color(0xFF272322),
    onSurfaceVariant = Color(0xFF9C9795),
    outline = Color(0xFF383433),
    outlineVariant = Color(0xFF272322),
    primary = Color(0xFFF69370),
    onPrimary = Color(0xFF331105),
    primaryContainer = Color(0xFF582818),
    onPrimaryContainer = Color(0xFFFFD0B8),
    secondary = Color(0xFF71D0D5),          // teal accent, info
    onSecondary = Color(0xFF00363A),
    tertiary = Color(0xFF76CF8A),           // success green
    onTertiary = Color(0xFF06371A),
    error = Color(0xFFFB817A),
    onError = Color(0xFF3D0705),
    errorContainer = Color(0xFF521615),
    onErrorContainer = Color(0xFFFFD8D4),
)

private val LightColors = lightColorScheme(
    background = Color(0xFFFAF8F7),
    onBackground = Color(0xFF131110),
    surface = Color(0xFFF2EFEE),
    onSurface = Color(0xFF131110),
    surfaceContainer = Color(0xFFE7E4E2),
    surfaceContainerHigh = Color(0xFFDAD6D5),
    onSurfaceVariant = Color(0xFF595452),
    outline = Color(0xFFB1ADAB),
    outlineVariant = Color(0xFFDAD6D5),
    primary = Color(0xFFAF5331),
    onPrimary = Color(0xFFFFFAF6),
    primaryContainer = Color(0xFFFFD2BE),
    onPrimaryContainer = Color(0xFF4B1300),
    secondary = Color(0xFF217D96),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF298646),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFC53637),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD8D4),
    onErrorContainer = Color(0xFF521615),
)

@Composable
fun GraballTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
