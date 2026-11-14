package com.example.dueo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dueo.ui.SquarePhotoCropper
import com.example.dueo.ui.theme.DueoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CropperUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun squareFrameSupportsZoomPanAndExportsSquare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.rgb(130, 100, 240))
            drawRect(300f, 0f, 600f, 900f, Paint().apply { color = Color.rgb(50, 150, 210) })
            drawCircle(300f, 450f, 130f, Paint().apply { color = Color.WHITE })
        }
        val file = File(context.cacheDir, "cropper-ui-source.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var output: String? = null
        compose.setContent { DueoTheme(true) { Surface { SquarePhotoCropper(Uri.fromFile(file), {}, { output = it }) } } }
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Use this crop") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Photo zoom").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithContentDescription("Square crop preview, drag to position photo").performScrollTo().performTouchInput { swipeLeft() }
        compose.onNodeWithText("1:1 square").assertIsDisplayed()
        compose.waitForIdle()
        File(context.getExternalFilesDir(null), "crop-preview.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Use this crop").performScrollTo().performClick()
        compose.waitUntil(10000) { output != null }
        val result = BitmapFactory.decodeFile(output)
        assertEquals(512, result.width); assertEquals(512, result.height)
    }
}
