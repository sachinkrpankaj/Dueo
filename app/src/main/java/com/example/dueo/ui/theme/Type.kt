package com.example.dueo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.dueo.R

val DueoFont = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold)
)
private fun style(size: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = DueoFont, fontWeight = weight, fontSize = size.sp,
        lineHeight = (size * 1.35f).sp, letterSpacing = tracking.sp)
val Typography = Typography(
    displayLarge = style(56, FontWeight.SemiBold, -2f),
    displayMedium = style(48, FontWeight.SemiBold, -1.8f),
    displaySmall = style(42, FontWeight.SemiBold, -1.4f),
    headlineLarge = style(38, FontWeight.SemiBold, -1.2f),
    headlineMedium = style(26, FontWeight.SemiBold, -.8f),
    headlineSmall = style(23, FontWeight.SemiBold, -.6f),
    titleLarge = style(19, FontWeight.SemiBold, -.5f),
    titleMedium = style(14, FontWeight.SemiBold, -.2f),
    titleSmall = style(13, FontWeight.Medium),
    bodyLarge = style(15), bodyMedium = style(13), bodySmall = style(12),
    labelLarge = style(13, FontWeight.SemiBold),
    labelMedium = style(12, FontWeight.Medium), labelSmall = style(11)
)
