package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = SaffronGold,
    onPrimary = OnSaffron,
    primaryContainer = SaffronContainer,
    onPrimaryContainer = SaffronGold,
    secondary = TerracottaCoral,
    onSecondary = Color(0xFF2B0903),
    secondaryContainer = TerracottaContainer,
    onSecondaryContainer = Color(0xFFFFB4A3),
    tertiary = ElectricCyan,
    onTertiary = Color(0xFF002130),
    tertiaryContainer = CyanContainer,
    onTertiaryContainer = Color(0xFFBAE6FD),
    background = ObsidianNight,
    onBackground = IvoryText,
    surface = ObsidianSurface,
    onSurface = IvoryText,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = MutedSilver,
    surfaceContainerLowest = ObsidianNight,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceVariant,
    surfaceContainerHigh = ObsidianElevated,
    outline = ObsidianBorder,
    outlineVariant = Color(0xFF2C2740),
    error = ErrorCrimson,
    onError = Color(0xFF33080C),
    errorContainer = ErrorContainer,
    onErrorContainer = Color(0xFFFECACA)
)

private val StudioLightColorScheme = lightColorScheme(
    primary = SaffronDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE5C4),
    onPrimaryContainer = Color(0xFF3A1F00),
    secondary = Color(0xFFD64527),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD2),
    onSecondaryContainer = Color(0xFF410E04),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    background = WarmAlabaster,
    onBackground = DeepIndigoInk,
    surface = Color.White,
    onSurface = DeepIndigoInk,
    surfaceVariant = SoftParchment,
    onSurfaceVariant = MutedIndigoInk,
    outline = Color(0xFFD6CEC2),
    error = Color(0xFFDC2626),
    onError = Color.White
)

val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun HindiDubStudioTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> StudioDarkColorScheme
        else -> StudioLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StudioTypography,
        shapes = StudioShapes,
        content = content
    )
}
