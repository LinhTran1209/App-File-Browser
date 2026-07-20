package com.j2team.fileserver.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF303F9F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF0FF),
    onPrimaryContainer = Color(0xFF030712),
    secondary = Color(0xFF098EA4),
    onSecondary = Color.White,
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF030712),
    surface = Color.White,
    onSurface = Color(0xFF030712),
    onSurfaceVariant = Color(0xFF374151),
    outline = Color(0xFFE5E7EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEB7FF),
    onPrimary = Color(0xFF101B75),
    primaryContainer = Color(0xFF28357E),
    onPrimaryContainer = Color(0xFFEEF0FF),
    secondary = Color(0xFF63D4E7),
    onSecondary = Color(0xFF00363D),
    background = Color(0xFF101114),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF181A1F),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFFD1D5DB),
    outline = Color(0xFF3F434C),
)

private val FileServerTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val FileServerShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(50),
)

object FileServerSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}

@Composable
fun FileServerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = FileServerTypography,
        shapes = FileServerShapes,
        content = content,
    )
}
