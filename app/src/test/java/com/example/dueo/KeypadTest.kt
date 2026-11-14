package com.example.dueo

import com.example.dueo.ui.editAmount
import org.junit.Assert.assertEquals
import org.junit.Test

class KeypadTest {
    @Test fun decimalPrecisionAndBackspace() {
        var amount = ""
        listOf("1", "0", ".", "0", "1", "9").forEach { amount = editAmount(amount, it) }
        assertEquals("10.01", amount)
        assertEquals("10.0", editAmount(amount, "⌫"))
        assertEquals("0.", editAmount("", "."))
        assertEquals("0.1", editAmount("0.1", "."))
    }
    @Test fun preventsOversizeAndInvalidInput() {
        assertEquals("123456789", editAmount("123456789", "0"))
        assertEquals("5", editAmount("0", "5"))
        assertEquals("", editAmount("", "⌫"))
        assertEquals("12", editAmount("12", "x"))
    }
}
