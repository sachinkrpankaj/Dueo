package com.example.dueo.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.dueo.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SquarePhotoCropper(uri: Uri, onCancel: () -> Unit, onCropped: (String) -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var zoom by rememberSaveable(uri.toString()) { mutableFloatStateOf(1f) }
    var centerX by rememberSaveable(uri.toString()) { mutableFloatStateOf(.5f) }
    var centerY by rememberSaveable(uri.toString()) { mutableFloatStateOf(.5f) }
    var viewport by remember { mutableIntStateOf(1) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(uri) {
        try { bitmap = withContext(Dispatchers.IO) { loadPhoto(context, uri) } }
        catch (_: Exception) { error = "Couldn't open this photo. Choose another image; contact details are kept." }
    }
    val image = bitmap
    val adjust: (Float, Offset) -> Unit = { newZoom, pan ->
        if (image != null && !saving) {
            val old = squareCrop(image.width, image.height, zoom, centerX, centerY)
            val x = (old.left + old.side / 2f - pan.x * old.side / viewport) / image.width
            val y = (old.top + old.side / 2f - pan.y * old.side / viewport) / image.height
            zoom = newZoom.coerceIn(1f, 5f)
            val next = squareCrop(image.width, image.height, zoom, x, y)
            centerX = (next.left + next.side / 2f) / image.width
            centerY = (next.top + next.side / 2f) / image.height
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, enabled = !saving) { LineIcon(Glyph.BACK, "Cancel crop") }
            Text("Crop photo", style = MaterialTheme.typography.headlineMedium)
        }
        Text("Drag to position. Pinch or use the slider to zoom. Only the square is saved.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            if (image == null && error == null) CircularProgressIndicator()
            if (image != null) {
                val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
                Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it.width.coerceAtLeast(1) }
                    .semantics { contentDescription = "Square crop preview, drag to position photo" }
                    .pointerInput(image, saving) { detectTransformGestures { _, pan, change, _ -> adjust(zoom * change, pan) } }) {
                    val crop = squareCrop(image.width, image.height, zoom, centerX, centerY)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawBitmap(image, Rect(crop.left, crop.top, crop.left + crop.side, crop.top + crop.side),
                            Rect(0, 0, size.width.toInt(), size.height.toInt()), paint)
                    }
                    for (i in 1..2) {
                        val offset = size.width * i / 3f
                        drawLine(Color.White.copy(alpha = .5f), Offset(offset, 0f), Offset(offset, size.height), 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = .5f), Offset(0f, offset), Offset(size.width, offset), 1.dp.toPx())
                    }
                    drawRect(Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1:1 square", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { zoom = 1f; centerX = .5f; centerY = .5f }, enabled = image != null && !saving) { Text("Reset crop") }
        }
        Slider(value = zoom, onValueChange = { adjust(it, Offset.Zero) }, valueRange = 1f..5f, enabled = image != null && !saving,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Photo zoom" })
        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            if (image != null && !saving) {
                saving = true
                scope.launch {
                    try {
                        val crop = squareCrop(image.width, image.height, zoom, centerX, centerY)
                        val path = withContext(Dispatchers.IO) { saveCrop(context, image, crop) }
                        onCropped(path)
                    } catch (_: Exception) { error = "Couldn't crop this photo. Please try again."; saving = false }
                }
            }
        }, enabled = image != null && !saving, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if (saving) "Cropping…" else "Use this crop") }
        TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
