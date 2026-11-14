package com.example.dueo

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dueo.data.*
import com.example.dueo.ui.EntryForm
import com.example.dueo.ui.theme.DueoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReferenceFlowUiTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(200) // Allow the Android render thread to present the settled shared-element frame.
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun avatarGridTransitionsToKeypadAndKeepsSelectedPerson() {
        val people = listOf("Sarah", "Pavlo", "Jordan", "Chris", "Emily", "Taylor").map { Person(id = it, name = it) }
        var savedPerson = ""
        compose.setContent { DueoTheme(false) { Surface {
            EntryForm(emptyList(), EntryKind.LENT, "", false, people, {}) { id, _, _, _ -> savedPerson = id }
        } } }
        compose.onNodeWithText("Pavlo").assertIsDisplayed()
        capture("recipient-grid")
        compose.onNodeWithText("Pavlo").performClick()
        compose.onNodeWithContentDescription("Digit 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Digit 4").performClick()
        compose.onNodeWithContentDescription("Digit 3").performClick()
        capture("amount-keypad")
        compose.onNodeWithText("Review transaction").performScrollTo().performClick()
        compose.onNodeWithText("Pavlo").assertIsDisplayed()
        compose.onNodeWithText("₹143.00").assertIsDisplayed()
        compose.onNodeWithText("Save transaction").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Pavlo", savedPerson) }
    }
}
