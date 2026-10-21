package dev.lmchat.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = LMPrimary,
    onPrimary = LMOnPrimary,
    primaryContainer = LMPrimaryContainer,
    onPrimaryContainer = LMOnPrimaryContainer,
    secondary = LMSecondary,
    onSecondary = LMOnSecondary,
    secondaryContainer = LMSecondaryContainer,
    onSecondaryContainer = LMOnSecondaryContainer,
    background = LMBackground,
    onBackground = LMOnSurface,
    surface = LMSurface,
    onSurface = LMOnSurface,
    surfaceVariant = LMSurfaceVariant,
    onSurfaceVariant = LMOnSurfaceVariant,
    outline = LMOutline,
    outlineVariant = LMOutlineVariant,
    error = LMError,
    onError = LMOnError,
    inverseSurface = LMOnSurface,
    inverseOnSurface = LMBackground,
    inversePrimary = LMPrimaryContainer,
)

/** The app is intentionally always-dark (user preference). */
@Composable
fun LMChatTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content,
    )
}
