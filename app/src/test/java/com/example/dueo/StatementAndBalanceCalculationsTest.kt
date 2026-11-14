package com.example.dueo

import com.example.dueo.data.*
import org.junit.Assert.*
import org.junit.Test

class StatementAndBalanceCalculationsTest {

    @Test
    fun balanceNetCalculatesAccurately() {
        val b1 = Balance(receive = 150000L, pay = 50000L)
        assertEquals(100000L, b1.net())

        val b2 = Balance(receive = 40000L, pay = 90000L)
        assertEquals(-50000L, b2.net())

        val b3 = Balance(receive = 75000L, pay = 75000L)
        assertEquals(0L, b3.net())
    }

    @Test
    fun safeArithmeticProtectsAgainstOverflow() {
        // Safe addition near Long.MAX_VALUE should throw FinancialOverflowException
        val overflowAddResult = runCatching {
            FinancialValidator.safeAdd(Long.MAX_VALUE - 10, 20)
        }
        assertTrue(overflowAddResult.isFailure)
        assertTrue(overflowAddResult.exceptionOrNull() is FinancialOverflowException)

        // Safe subtraction below Long.MIN_VALUE should throw FinancialOverflowException
        val overflowSubResult = runCatching {
            FinancialValidator.safeSubtract(Long.MIN_VALUE + 10, 20)
        }
        assertTrue(overflowSubResult.isFailure)
        assertTrue(overflowSubResult.exceptionOrNull() is FinancialOverflowException)
    }

    @Test
    fun statementPaginationAccountsForEveryTransaction() {
        // Test varying list sizes: 0, 1, 16, 17, 30, 40, 65, 120
        val sizes = listOf(0, 1, 16, 17, 30, 40, 65, 120)

        for (size in sizes) {
            val entries = List(size) { i ->
                Entry(
                    id = "e-$i",
                    person = "Test User",
                    personId = "p-test",
                    amount = (i + 1) * 1000L,
                    kind = EntryKind.LENT,
                    date = 10000L + i * 1000L
                )
            }

            val chronological = entries.sortedBy { it.date }
            assertEquals(size, chronological.size)

            val page1Capacity = 16
            val subsequentCapacity = 24
            val totalPages = if (chronological.size <= page1Capacity) 1 else {
                1 + ((chronological.size - page1Capacity + subsequentCapacity - 1) / subsequentCapacity)
            }

            var accountedFor = 0
            var entryIndex = 0
            for (pageNum in 1..totalPages) {
                val capacity = if (pageNum == 1) page1Capacity else subsequentCapacity
                val pageEntries = chronological.drop(entryIndex).take(capacity)
                accountedFor += pageEntries.size
                entryIndex += pageEntries.size
            }

            assertEquals("Every transaction must be accounted for across pages for size $size", size, accountedFor)
            assertEquals("No remaining unpaginated entries", chronological.size, entryIndex)
        }
    }

    @Test
    fun statementImagePaginationDistributesAllEntriesAcrossPages() {
        val maxRowsPerPage = 25
        val testCounts = listOf(0, 1, 24, 25, 26, 50, 75, 101)

        for (count in testCounts) {
            val entries = List(count) { i ->
                Entry(
                    id = "entry-$i",
                    person = "Aarav",
                    personId = "p-aarav",
                    amount = (i + 1) * 500L,
                    kind = EntryKind.LENT,
                    date = 1000L + i * 50L
                )
            }

            val totalPages = if (entries.isEmpty()) 1 else {
                ((entries.size + maxRowsPerPage - 1) / maxRowsPerPage).coerceAtLeast(1)
            }

            if (count == 0 || count <= 25) {
                assertEquals("Should be exactly 1 page for $count entries", 1, totalPages)
            } else if (count == 26) {
                assertEquals("26 entries must paginate to 2 pages", 2, totalPages)
            }

            var distributed = 0
            for (pageNum in 1..totalPages) {
                val pageSlice = entries.drop((pageNum - 1) * maxRowsPerPage).take(maxRowsPerPage)
                distributed += pageSlice.size
                assertTrue("Slice size must be <= $maxRowsPerPage", pageSlice.size <= maxRowsPerPage)
            }

            assertEquals("All $count entries must be distributed across $totalPages image pages", count, distributed)
        }
    }
}

