package com.example.dueo.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dueo.R
import com.example.dueo.ui.theme.DueoDesign

// Small coherent line-icon set, with no font glyphs or extra icon dependency.
enum class Glyph(val path: String) {
    HOME("M3,10 L12,3 L21,10 M5,9 L5,21 L10,21 L10,15 L14,15 L14,21 L19,21 L19,9"),
    PEOPLE("M15,21 L15,18 C15,14 3,14 3,18 L3,21 M9,4 A4,4 0,1 1,9,12 A4,4 0,1 1,9,4 M17,4 C22,4 22,12 17,12 M19,15 C22,15 22,18 22,21"),
    ACTIVITY("M5,3 L19,3 L19,21 L5,21 Z M8,8 L16,8 M8,12 L16,12 M8,16 L13,16"),
    SETTINGS("M4,6 L20,6 M4,12 L20,12 M4,18 L20,18 M8,3 L8,9 M16,9 L16,15 M10,15 L10,21"),
    ADD("M12,5 L12,19 M5,12 L19,12"),
    LEND("M6,18 L18,6 M7,6 L18,6 L18,17"),
    BORROW("M18,6 L6,18 M6,7 L6,18 L17,18"),
    MOON("M20,14 A9,9 0,1 1,10,3 A7,7 0,0 0,20,14 Z"),
    SUN("M12,7 A5,5 0,1 1,12,17 A5,5 0,1 1,12,7 M12,1 L12,3 M12,21 L12,23 M1,12 L3,12 M21,12 L23,12 M4,4 L5,5 M19,19 L20,20 M4,20 L5,19 M19,5 L20,4"),
    BACK("M15,5 L8,12 L15,19"),
    SEARCH("M10,3 A7,7 0,1 1,10,17 A7,7 0,1 1,10,3 M15,15 L21,21"),
    CHEVRON("M9,5 L16,12 L9,19")
}

@Composable
fun LineIcon(glyph: Glyph, description: String? = null, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    val vector = remember(glyph) {
        ImageVector.Builder(glyph.name, 24.dp, 24.dp, 24f, 24f).addPath(
            PathParser().parsePathString(glyph.path).toNodes(), pathFillType = PathFillType.NonZero,
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        ).build()
    }
    Icon(vector, description, modifier.size(22.dp), tint)
}

@Composable
fun BrandHeader(dark: Boolean, onTheme: () -> Unit, onDarkSurface: Boolean = dark) {
    Row(Modifier.enterMotion().fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(Modifier.padding(start = 7.dp, end = 14.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(if (onDarkSurface) R.drawable.dueo_mark_dark else R.drawable.dueo_mark_light), null, Modifier.size(32.dp))
                Text("dueo", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
            }
        }
        Spacer(Modifier.weight(1f))
        val rotation by animateFloatAsState(if (dark) 180f else 0f, tween(550), label = "theme rotation")
        IconButton(onClick = onTheme) {
            Crossfade(dark, animationSpec = tween(350), label = "theme icon") { isDark ->
                LineIcon(if (isDark) Glyph.SUN else Glyph.MOON, "Choose theme", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.rotate(rotation))
            }
        }
    }
}

@Composable
fun PillNavigation(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = DueoDesign.Nav, shape = CircleShape, shadowElevation = 10.dp,
        modifier = Modifier.enterMotion(3).padding(horizontal = 22.dp, vertical = 10.dp).navigationBarsPadding().fillMaxWidth()) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Home" to Glyph.HOME, "People" to Glyph.PEOPLE, "Activity" to Glyph.ACTIVITY, "Settings" to Glyph.SETTINGS).forEachIndexed { index, (name, icon) ->
                val active = selected == index
                val bg by animateColorAsState(if (active) Color.White.copy(alpha = .92f) else Color.Transparent, label = "nav")
                val weight by animateFloatAsState(if (active) 1.65f else 1f, spring(dampingRatio = .8f, stiffness = 350f), label = "nav width")
                val source = remember { MutableInteractionSource() }
                Row(Modifier.weight(weight).pressMotion(source).clip(CircleShape).background(bg)
                    .semantics { this.selected = active; contentDescription = name }
                    .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(index) }.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    LineIcon(icon, tint = if (active) DueoDesign.Nav else Color(0xFFBFC0CA))
                    AnimatedVisibility(active, enter = fadeIn(tween(220)) + expandHorizontally(), exit = fadeOut(tween(120)) + shrinkHorizontally()) {
                        Row { Spacer(Modifier.width(7.dp)); Text(name, color = DueoDesign.Nav, fontSize = 11.sp, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickAction(label: String, glyph: Glyph, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .85f, stiffness = 600f), label = "action press")
    Surface(modifier = modifier.enterMotion(if (primary) 2 else 3).scale(scale), shape = DueoDesign.SmallShape, shadowElevation = if (primary) 2.dp else 0.dp,
        color = if (primary) Color.Transparent else MaterialTheme.colorScheme.surface) {
        Column(Modifier.then(if (primary) Modifier.background(DueoDesign.Gradient) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp).padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)) {
            LineIcon(glyph, tint = if (primary) Color.White else MaterialTheme.colorScheme.onSurface)
            Text(label, color = if (primary) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, photoPath: String? = null) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, photoPath) {
        value = withContext(Dispatchers.IO) { photoPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
    }
    Box(modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
        else Text(name.trim().split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
