package com.example.dueo

import com.example.dueo.data.*
import org.junit.Assert.*
import org.junit.Test

class LedgerTest {
    @Test fun amountsUseIndianGrouping() {
        assertEquals("₹12,34,567.89", money(123456789))
        assertEquals("−₹1,000.01", money(-100001))
        assertEquals("₹0.00", money(0))
    }
    @Test fun amountsUseExactPaise() {
        assertEquals(1001L, parseAmount("10.01"))
        assertEquals(10L, parseAmount("0.10"))
        listOf("0", "-1", "1.234", "1e3", "NaN", "", "1000000000").forEach { assertNull(parseAmount(it)) }
    }
    @Test fun lendingAndBorrowingRemainSeparateAfterRepayments() {
        val entries = listOf(
            Entry(person = "Alex", amount = 10001, kind = EntryKind.LENT),
            Entry(person = "Alex", amount = 4000, kind = EntryKind.BORROWED),
            Entry(person = "Alex", amount = 1001, kind = EntryKind.RECEIVED),
            Entry(person = "Alex", amount = 500, kind = EntryKind.PAID)
        )
        assertEquals(Balance(receive = 9000, pay = 3500), entries.balance())
    }
    @Test fun emptyLedgerStartsAtZero() { assertEquals(Balance(), emptyList<Entry>().balance()) }
}
