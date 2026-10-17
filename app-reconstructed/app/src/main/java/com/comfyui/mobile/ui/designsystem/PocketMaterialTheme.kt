package com.comfyui.mobile.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF335DB8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF), onPrimaryContainer = Color(0xFF102B61),
    secondary = Color(0xFF565F76), secondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFF41686A), tertiaryContainer = Color(0xFFC4EAEB),
    background = Color(0xFFF9F9FF), surface = Color(0xFFF9F9FF),
    surfaceContainerLow = Color(0xFFF0F3FC), surfaceContainer = Color(0xFFEBEEF7),
    surfaceContainerHigh = Color(0xFFE5E9F2), outline = Color(0xFF747B8B)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF), onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF22467F), onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBEC6DC), secondaryContainer = Color(0xFF3E475B),
    tertiary = Color(0xFFA8CED0), tertiaryContainer = Color(0xFF284F51),
    background = Color(0xFF111318), surface = Color(0xFF111318),
    surfaceContainerLow = Color(0xFF191C22), surfaceContainer = Color(0xFF1D2028),
    surfaceContainerHigh = Color(0xFF282C35), outline = Color(0xFF8E96A8)
)

/** No legacy composition locals or old theme preferences cross this boundary. */
@Composable
fun PocketMaterialTheme(
    dark: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false,
    colorScheme: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    val colors = colorScheme ?: when {
        dynamicColor && dark -> dynamicDarkColorScheme(LocalContext.current)
        dynamicColor -> dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors, typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp)
        ), content = content
    )
}
