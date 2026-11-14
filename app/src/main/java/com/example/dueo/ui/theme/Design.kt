package com.example.dueo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object DueoDesign {
    val PagePadding = 22.dp
    val CardShape = RoundedCornerShape(24.dp)
    val SmallShape = RoundedCornerShape(18.dp)
    val Gradient = Brush.linearGradient(listOf(Color(0xFF9585FF), Color(0xFF7160F3), Color(0xFF5642CC)))
    val Nav = Color(0xFF242528)
    val Shapes = Shapes(small = RoundedCornerShape(12.dp), medium = SmallShape, large = CardShape)
}
