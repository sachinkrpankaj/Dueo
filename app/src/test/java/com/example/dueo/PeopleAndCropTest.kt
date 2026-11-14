package com.example.dueo

import com.example.dueo.data.*
import org.junit.Assert.*
import org.junit.Test

class PeopleAndCropTest {
    @Test fun legacyTransactionsKeepAmountsAndGetStablePersonLinks() {
        val old = listOf(Entry(person = "Alex", amount = 10001, kind = EntryKind.LENT), Entry(person = "Alex", amount = 301, kind = EntryKind.RECEIVED), Entry(person = "Sam", amount = 2000, kind = EntryKind.BORROWED))
        val migrated = migrateEntries(old)
        assertEquals(2, migrated.people.size)
        assertEquals(old.balance(), migrated.entries.balance())
        assertEquals(old.map { it.id }, migrated.entries.map { it.id })
        assertEquals(migrated.entries[0].personId, migrated.entries[1].personId)
        assertNotEquals(migrated.entries[0].personId, migrated.entries[2].personId)
    }
    @Test fun squareCropIsBoundedInPortraitAndLandscape() {
        for ((width, height) in listOf(800 to 400, 400 to 800, 512 to 512)) {
            for (zoom in listOf(1f, 2f, 5f)) for (x in listOf(-1f, .5f, 2f)) for (y in listOf(-1f, .5f, 2f)) {
                val crop = squareCrop(width, height, zoom, x, y)
                assertTrue(crop.left >= 0 && crop.top >= 0 && crop.side > 0)
                assertTrue(crop.left + crop.side <= width)
                assertTrue(crop.top + crop.side <= height)
            }
        }
        assertEquals(SquareCrop(200, 0, 400), squareCrop(800, 400, 1f, .5f, .5f))
        assertEquals(SquareCrop(500, 100, 200), squareCrop(800, 400, 2f, .75f, .5f))
    }
}
