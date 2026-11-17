package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = IndigoDarkPrimary,
    onPrimary = IndigoDarkOnPrimary,
    primaryContainer = IndigoDarkPrimaryContainer,
    onPrimaryContainer = IndigoDarkOnPrimaryContainer,
    secondary = IndigoDarkSecondary,
    tertiary = IndigoDarkTertiary,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = IndigoOnPrimary,
    primaryContainer = IndigoPrimaryContainer,
    onPrimaryContainer = IndigoOnPrimaryContainer,
    secondary = IndigoSecondary,
    onSecondary = IndigoOnSecondary,
    secondaryContainer = IndigoSecondaryContainer,
    onSecondaryContainer = IndigoOnSecondaryContainer,
    tertiary = IndigoTertiary,
    onTertiary = IndigoOnTertiary,
    tertiaryContainer = IndigoTertiaryContainer,
    onTertiaryContainer = IndigoOnTertiaryContainer,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is false by default so Indigo theme is explicitly applied
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
