package com.example.dueo

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.dueo.data.*
import com.example.dueo.ui.EntryForm
import com.example.dueo.ui.PersonEditor
import com.example.dueo.ui.theme.DueoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PersonProfileUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nameRequiredAndPersonCanBeSavedWithoutTransaction() {
        var savedName = ""
        compose.setContent { DueoTheme { PersonEditor(false, onDismiss = {}) { name, phone, photo ->
            savedName = name; assertEquals("", phone); assertNull(photo)
        } } }
        compose.onNodeWithText("Save person").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Name *").performScrollTo().performTextInput("  Alex  ")
        compose.onNodeWithText("Save person").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Alex", savedName) }
    }
    @Test fun cannotInventPersonInTransactionFlow() {
        var adding = false
        compose.setContent { DueoTheme { Surface {
            EntryForm(emptyList(), EntryKind.LENT, "", false, emptyList(), { adding = true }) { _, _, _, _ -> error("Must add a person first") }
        } } }
        compose.onNodeWithText("Search people").performTextInput("Unregistered person")
        compose.onNodeWithText("Review transaction").assertDoesNotExist()
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(adding) }
    }
}
