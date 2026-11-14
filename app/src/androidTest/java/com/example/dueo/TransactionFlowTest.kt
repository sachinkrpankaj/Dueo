package com.example.dueo

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.dueo.data.*
import com.example.dueo.ui.EntryForm
import com.example.dueo.ui.theme.DueoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TransactionFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun keypadReviewAndSingleSave() {
        var savedAmount = 0L
        var count = 0
        var saving by mutableStateOf(false)
        compose.setContent { DueoTheme { Surface {
            EntryForm(emptyList(), EntryKind.LENT, "alex-id", saving, listOf(Person(id = "alex-id", name = "Alex")), {}) { _, amount, _, _ ->
                savedAmount = amount; count++; saving = true
            }
        } } }
        listOf("Digit 1", "Digit 0", "Decimal point", "Digit 0", "Digit 1").forEach {
            compose.onNodeWithContentDescription(it).performScrollTo().performClick()
        }
        compose.onNodeWithText("Review transaction").performScrollTo().performClick()
        compose.onNodeWithText("₹10.01").assertIsDisplayed()
        compose.onNodeWithText("Save transaction").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1001L, savedAmount); assertEquals(1, count) }
        compose.onNodeWithText("Saving…").assertIsDisplayed()
    }
    @Test fun repaymentCannotExceedOutstanding() {
        compose.setContent { DueoTheme { Surface {
            EntryForm(listOf(Entry(person = "Alex", amount = 1000, kind = EntryKind.LENT, personId = "alex-id")), EntryKind.RECEIVED, "alex-id", false, listOf(Person(id = "alex-id", name = "Alex")), {}) { _, _, _, _ -> error("Invalid repayment saved") }
        } } }
        compose.onNodeWithContentDescription("Digit 2").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Digit 0").performScrollTo().performClick()
        compose.onNodeWithText("Review transaction").performScrollTo().performClick()
        compose.onNodeWithText("You can receive up to ₹10.00.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Looking good?").assertDoesNotExist()
    }
}
