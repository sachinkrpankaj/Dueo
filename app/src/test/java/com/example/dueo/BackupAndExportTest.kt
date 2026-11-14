package com.example.dueo

import com.example.dueo.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupAndExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun exportProducesCompleteValidJsonPayload() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()
        val person = repository.addPerson("Aaditya", "9898989898", null).getOrThrow()
        repository.addTransaction(
            personId = person.id,
            amount = 75000L,
            kind = EntryKind.LENT,
            note = "Hotel stay split",
            dueDate = 1750000000000L
        ).getOrThrow()

        val jsonResult = repository.exportLedgerJson()
        assertTrue(jsonResult.isSuccess)
        val json = jsonResult.getOrThrow()

        val root = JSONObject(json)
        assertEquals("Dueo", root.getString("app"))
        assertEquals(1, root.getInt("backupSchemaVersion"))
        assertTrue(root.getLong("exportedAt") > 0)

        val people = root.getJSONArray("people")
        assertEquals(1, people.length())
        assertEquals("Aaditya", people.getJSONObject(0).getString("name"))

        val entries = root.getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals(75000L, entries.getJSONObject(0).getLong("amount"))
        assertEquals("LENT", entries.getJSONObject(0).getString("kind"))
        assertEquals("Hotel stay split", entries.getJSONObject(0).getString("note"))
        assertEquals(1750000000000L, entries.getJSONObject(0).getLong("dueDate"))
    }

    @Test
    fun restoreValidBackupReplacesStateCorrectly() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()

        val backupJson = """{
            "app": "Dueo",
            "backupSchemaVersion": 1,
            "exportedAt": 1700000000000,
            "people": [
                {"id": "p-100", "name": "Deepa", "phone": "12345", "photoPath": ""}
            ],
            "entries": [
                {"id": "e-100", "person": "Deepa", "personId": "p-100", "amount": 150000, "kind": "LENT", "note": "Trip", "date": 1000},
                {"id": "e-101", "person": "Deepa", "personId": "p-100", "amount": 50000, "kind": "RECEIVED", "note": "Cash", "date": 2000}
            ]
        }"""

        val root = JSONObject(backupJson)
        val peopleArray = root.getJSONArray("people")
        val entriesArray = root.getJSONArray("entries")

        val people = List(peopleArray.length()) { i ->
            val obj = peopleArray.getJSONObject(i)
            Person(obj.getString("id"), obj.getString("name"), obj.optString("phone"), null)
        }
        val entries = List(entriesArray.length()) { i ->
            store.parseEntry(entriesArray.getJSONObject(i))
        }

        val restoreResult = repository.restoreLedger(LedgerState(people, entries))
        assertTrue(restoreResult.isSuccess)

        val state = repository.getCurrentState()
        assertNotNull(state)
        assertEquals(1, state!!.people.size)
        assertEquals("Deepa", state.people[0].name)
        assertEquals(2, state.entries.size)
        assertEquals(100000L, state.entries.balance().receive)
    }

    @Test
    fun restoreCorruptedRepaymentsFailsSafely() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()
        val originalPerson = repository.addPerson("Original", "111", null).getOrThrow()

        // Invalid: repayment of ₹500 with only ₹200 loan
        val invalidEntries = listOf(
            Entry(id = "e-1", person = "Bad", personId = "p-bad", amount = 20000L, kind = EntryKind.LENT, date = 1000),
            Entry(id = "e-2", person = "Bad", personId = "p-bad", amount = 50000L, kind = EntryKind.RECEIVED, date = 2000)
        )
        val invalidState = LedgerState(
            people = listOf(Person(id = "p-bad", name = "Bad")),
            entries = invalidEntries
        )

        val restoreResult = repository.restoreLedger(invalidState)
        assertTrue(restoreResult.isFailure)
        assertTrue(restoreResult.exceptionOrNull() is OverRepaymentException)

        // Original repository state must remain safe and unaltered
        val state = repository.getCurrentState()
        assertNotNull(state)
        assertEquals(1, state!!.people.size)
        assertEquals("Original", state.people[0].name)
    }

    @Test
    fun photoCropCleanupRemovesStagingFilesSafely() {
        val cacheDir = tempFolder.newFolder("cache")
        val cropFile = File(cacheDir, "person-crop-test-uuid.jpg").apply { writeText("fake image bytes") }
        val permanentPhoto = File(cacheDir, "saved-profile.jpg").apply { writeText("permanent photo") }

        assertTrue(cropFile.exists())
        cleanupCropFile(cropFile.absolutePath)
        assertFalse("Temporary crop file should be deleted", cropFile.exists())

        // Non-crop file must never be deleted
        cleanupCropFile(permanentPhoto.absolutePath)
        assertTrue("Non-crop file must remain untouched", permanentPhoto.exists())
    }
}

