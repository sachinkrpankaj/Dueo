package com.example.dueo

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dueo.data.*
import com.example.dueo.ui.*
import com.example.dueo.ui.theme.DueoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class DashboardUiTest {
    @get:Rule val compose = createComposeRule()
    private val samples = listOf(
        Entry(person = "Rahul Kumar", amount = 150000, kind = EntryKind.LENT),
        Entry(person = "Rahul Kumar", amount = 30000, kind = EntryKind.BORROWED),
        Entry(person = "Neha Sharma", amount = 50000, kind = EntryKind.BORROWED),
        Entry(person = "Alex Chen", amount = 80000, kind = EntryKind.LENT)
    )
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun dashboardThemesAndActions() {
        var dark by mutableStateOf(false)
        var action: EntryKind? = null
        var addOpened = false
        var selectedPerson = ""
        var destination = -1
        compose.setContent {
            DueoTheme(dark) {
                Scaffold(bottomBar = { PillNavigation(0) { destination = it } }) { inset ->
                    Box(Modifier.padding(inset)) {
                        HomeScreen(samples, dark, { dark = !dark }, { action = it; addOpened = true }, { destination = 1 }, { selectedPerson = it })
                    }
                }
            }
        }
        compose.onNodeWithText("Net dues").assertIsDisplayed()
        compose.onAllNodesWithText(money(150000))[0].assertIsDisplayed()
        screenshot("home-light")
        compose.onNodeWithText("Lend").performClick()
        compose.runOnIdle { assertEquals(EntryKind.LENT, action) }
        compose.onNodeWithText("Borrow").performClick()
        compose.runOnIdle { assertEquals(EntryKind.BORROWED, action) }
        compose.onNodeWithText("Add").performClick()
        compose.runOnIdle { assertEquals(true, addOpened); assertEquals(null, action) }
        compose.onAllNodesWithText("Rahul Kumar")[0].performClick()
        compose.runOnIdle { assertEquals("Rahul Kumar", selectedPerson) }
        compose.onNodeWithContentDescription("Choose theme").performClick()
        screenshot("home-dark")
        compose.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(3, destination) }
    }
    @Test fun emptyDashboardUsesOnlyRealZeroBalances() {
        compose.setContent {
            DueoTheme(true) { Scaffold(bottomBar = { PillNavigation(0) {} }) { inset ->
                Box(Modifier.padding(inset)) { HomeScreen(emptyList(), true, {}, {}, {}, {}) }
            } }
        }
        compose.onNodeWithText("Add your first person").assertIsDisplayed()
        compose.onNodeWithText("No dues yet").assertIsDisplayed()
        compose.onAllNodesWithText("₹0.00").assertCountEquals(3)
        screenshot("home-empty-dark")
    }
}
