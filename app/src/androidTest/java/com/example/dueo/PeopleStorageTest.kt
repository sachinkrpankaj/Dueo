package com.example.dueo

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dueo.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PeopleStorageTest {
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "people-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getCacheDir() = directory
        }
    }
    @Test fun zeroTransactionPeopleAndSameNamesPersistSeparately() {
        val context = isolatedContext()
        val store = LedgerStore(context)
        val first = store.addPerson(LedgerState(), "Alex", "111", null)
        val second = store.addPerson(first, "Alex", "222", null)
        val updated = second.copy(entries = listOf(Entry(person = "Alex", personId = second.people[1].id, amount = 1000, kind = EntryKind.LENT)))
        store.writeState(updated)
        val read = LedgerStore(context).readState()
        assertEquals(2, read.people.size)
        assertNotEquals(read.people[0].id, read.people[1].id)
        assertEquals("222", read.people[1].phone)
        assertEquals(second.people[1].id, read.entries.single().personId)
        assertTrue(read.entries.none { it.personId == read.people[0].id })
        assertTrue(runCatching { store.addPerson(read, "   ", "", null) }.isFailure)
    }
    @Test fun migrationIsIdempotentAndLeavesLegacyFileIntact() {
        val context = isolatedContext()
        val legacy = File(context.filesDir, "ledger-v1.json")
        val json = """[{"id":"entry-1","person":"Alex","amount":12345,"kind":"LENT","note":"old record","date":1000}]"""
        legacy.writeText(json)
        val first = LedgerStore(context).readState()
        val second = LedgerStore(context).readState()
        assertEquals(first, second)
        assertEquals(json, legacy.readText())
        assertEquals(12345L, second.entries.single().amount)
    }
    @Test fun savedPhotoUsesSelectedSquareAndSurvivesReload() {
        val context = isolatedContext()
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.RED); drawRect(200f, 0f, 400f, 200f, Paint().apply { color = Color.BLUE }) }
        val path = saveCrop(context, bitmap, squareCrop(400, 200, 2f, .75f, .5f))
        val store = LedgerStore(context)
        store.addPerson(LedgerState(), "Photo person", "", path)
        val persisted = store.readState().people.single().photoPath!!
        assertNotEquals(path, persisted)
        File(path).delete()
        val photo = BitmapFactory.decodeFile(persisted)
        assertEquals(512, photo.width); assertEquals(512, photo.height)
        assertTrue(Color.blue(photo.getPixel(256, 256)) > 240)
        assertTrue(Color.red(photo.getPixel(256, 256)) < 15)
    }
}
