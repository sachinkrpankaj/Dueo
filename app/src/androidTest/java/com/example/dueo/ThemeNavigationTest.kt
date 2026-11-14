package com.example.dueo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dueo.data.ThemeMode
import com.example.dueo.data.ThemePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThemeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun themePersistsAndNavigationSurvivesRecreation() {
        val preferences = ThemePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = runBlocking { preferences.mode.first() }
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Net dues").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Light").performClick()
            compose.waitUntil(5000) { runBlocking { preferences.mode.first() } == ThemeMode.LIGHT }
            compose.onNodeWithText("Dark").performClick()
            compose.waitUntil(5000) { runBlocking { preferences.mode.first() } == ThemeMode.DARK }
            compose.onNodeWithContentDescription("People", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Search people").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Search people").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(ThemeMode.DARK, runBlocking { preferences.mode.first() })
            compose.onNodeWithContentDescription("Activity", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("Add transaction").assertIsDisplayed()
            compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Add").performClick()
            compose.onNodeWithText("Repayment received").assertIsDisplayed()
            compose.onNodeWithText("Repayment made").assertIsDisplayed()
        } finally { runBlocking { preferences.set(original) } }
    }
}
