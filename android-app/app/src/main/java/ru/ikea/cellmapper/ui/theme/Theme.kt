package ru.ikea.cellmapper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IkeaDark = darkColorScheme(
    primary = Color(0xFFFACC15),
    onPrimary = Color(0xFF18181B),
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF1A1A1D),
    surface = Color(0xFF252529),
    onBackground = Color(0xFFE4E4E7),
    onSurface = Color(0xFFFAFAFA),
    error = Color(0xFFF87171)
)

@Composable
fun IkeaCellMapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IkeaDark,
        content = content
    )
}
