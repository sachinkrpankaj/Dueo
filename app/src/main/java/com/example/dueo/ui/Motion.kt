package com.example.dueo.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/** Finite, interruptible motion. Compose respects the system animator-duration scale. */
object DueoMotion {
    const val Duration = 380
    val Ease = CubicBezierEasing(.22f, 1f, .36f, 1f)
}

fun Modifier.enterMotion(order: Int = 0): Modifier = composed {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(if (appeared) 1f else 0f,
        tween(380, (order.coerceIn(0, 6) * 32), DueoMotion.Ease), label = "staggered entrance")
    val distance = with(LocalDensity.current) { 16f * density }
    graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * distance
        scaleX = .985f + progress * .015f
        scaleY = scaleX
    }
}

fun Modifier.pressMotion(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .965f else 1f,
        spring(dampingRatio = .85f, stiffness = 600f), label = "spring press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

fun pageMotion(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(tween(380, easing = DueoMotion.Ease)) { direction * it / 8 } +
        fadeIn(tween(220))) togetherWith
        (slideOutHorizontally(tween(320, easing = DueoMotion.Ease)) { -direction * it / 12 } + fadeOut(tween(150)))
}

fun sheetMotion(forward: Boolean): ContentTransform =
    (slideInVertically(tween(460, easing = DueoMotion.Ease)) { if (forward) it / 3 else -it / 10 } +
        fadeIn(tween(240)) + scaleIn(tween(460, easing = DueoMotion.Ease), initialScale = .98f)) togetherWith
    (fadeOut(tween(160)) + scaleOut(tween(360, easing = DueoMotion.Ease), targetScale = .95f))

@Composable
fun SuccessTick() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val progress by animateFloatAsState(if (visible) 1f else 0f, tween(500), label = "success check")
    val color = androidx.compose.material3.MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.then(Modifier).graphicsLayer { scaleX = .7f + .3f * progress; scaleY = scaleX }.then(Modifier.size(28.dp))) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .15f, size.height * .5f)
            if (progress < .4f) lineTo(size.width * (.15f + .25f * progress / .4f), size.height * (.5f + .25f * progress / .4f))
            else { lineTo(size.width * .4f, size.height * .75f); lineTo(size.width * (.4f + .45f * (progress - .4f) / .6f), size.height * (.75f - .5f * (progress - .4f) / .6f)) }
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
