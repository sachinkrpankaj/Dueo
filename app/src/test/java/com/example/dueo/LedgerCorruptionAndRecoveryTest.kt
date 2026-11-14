package com.example.dueo

import com.example.dueo.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LedgerCorruptionAndRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun missingLedgerReturnsEmptyResult() {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val result = store.loadLedger()
        assertTrue(result is LedgerLoadResult.Empty)
    }

    @Test
    fun writeAndReadRoundtripPreservesExactData() {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)

        val person = Person(id = "p-1", name = "Priya Sharma", phone = "9876543210")
        val entry = Entry(
            id = "e-1",
            person = "Priya Sharma",
            personId = "p-1",
            amount = 125050L,
            kind = EntryKind.LENT,
            note = "Grocery split",
            date = 1700000000000L,
            dueDate = 1705000000000L
        )
        val state = LedgerState(people = listOf(person), entries = listOf(entry))

        store.writeState(state)

        val loadResult = store.loadLedger()
        assertTrue(loadResult is LedgerLoadResult.Success)
        val loaded = (loadResult as LedgerLoadResult.Success).state

        assertEquals(1, loaded.people.size)
        assertEquals("Priya Sharma", loaded.people[0].name)
        assertEquals("9876543210", loaded.people[0].phone)

        assertEquals(1, loaded.entries.size)
        assertEquals(125050L, loaded.entries[0].amount)
        assertEquals(EntryKind.LENT, loaded.entries[0].kind)
        assertEquals("Grocery split", loaded.entries[0].note)
        assertEquals(1705000000000L, loaded.entries[0].dueDate)
    }

    @Test
    fun malformedJsonPreservesCorruptFileAndReturnsRecoverableFailure() {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)

        // Inject corrupt JSON file
        val corruptContent = """{"version": 2, "people": [{"id": "p1", "name": "Broken" ... truncated"""
        store.ledgerFile.writeText(corruptContent)

        val result = store.loadLedger()
        assertTrue(result is LedgerLoadResult.RecoverableFailure)
        val failure = result as LedgerLoadResult.RecoverableFailure
        assertTrue(failure.message.contains("malformed JSON"))

        // Original damaged file must be preserved
        assertTrue(store.ledgerFile.exists())
        assertEquals(corruptContent, store.ledgerFile.readText())

        // Timestamped backup file in corrupted_backups directory must exist
        assertNotNull(failure.backupFile)
        assertTrue(failure.backupFile!!.exists())
        assertEquals(corruptContent, failure.backupFile!!.readText())
    }

    @Test
    fun unsupportedSchemaVersionPreservesFileAndReturnsUnsupportedVersion() {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)

        val futureVersionJson = """{"version": 99, "people": [], "entries": []}"""
        store.ledgerFile.writeText(futureVersionJson)

        val result = store.loadLedger()
        assertTrue(result is LedgerLoadResult.UnsupportedVersion)
        val unsupported = result as LedgerLoadResult.UnsupportedVersion
        assertEquals(99, unsupported.version)
        assertNotNull(unsupported.backupFile)
        assertTrue(unsupported.backupFile!!.exists())
    }

    @Test
    fun corruptedFinancialRecordsPreservedAndRejected() {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)

        // Valid JSON syntax, but negative amount (invalid financial invariant)
        val invalidFinancialJson = """{
            "version": 2,
            "people": [{"id": "p1", "name": "Bob", "phone": "", "photoPath": ""}],
            "entries": [{"id": "e1", "person": "Bob", "personId": "p1", "amount": -500, "kind": "LENT", "note": "", "date": 1000}]
        }"""
        store.ledgerFile.writeText(invalidFinancialJson)

        val result = store.loadLedger()
        assertTrue(result is LedgerLoadResult.RecoverableFailure)
        val failure = result as LedgerLoadResult.RecoverableFailure
        assertTrue(failure.message.contains("invalid financial data"))
        assertNotNull(failure.backupFile)
        assertTrue(failure.backupFile!!.exists())
    }

    @Test
    fun legacyV1MigrationPreservesLegacyFileUnchanged() {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)

        val legacyV1Json = """[
            {"id": "e1", "person": "Aarav", "amount": 50000, "kind": "LENT", "note": "Lunch", "date": 1000},
            {"id": "e2", "person": "Aarav", "amount": 20000, "kind": "RECEIVED", "note": "UPI", "date": 2000}
        ]"""
        store.legacyFile.writeText(legacyV1Json)

        val result = store.loadLedger()
        assertTrue(result is LedgerLoadResult.Success)
        val migrated = (result as LedgerLoadResult.Success).state

        assertEquals(1, migrated.people.size)
        assertEquals("Aarav", migrated.people[0].name)
        assertEquals(2, migrated.entries.size)
        assertEquals(30000L, migrated.entries.balance().receive)

        // Legacy file must remain 100% untouched
        assertTrue(store.legacyFile.exists())
        assertEquals(legacyV1Json, store.legacyFile.readText())

        // v2 file must exist now
        assertTrue(store.ledgerFile.exists())
    }

    @Test
    fun safeAtomicFileRecoversFromBackupWhenInterrupted() {
        val folder = tempFolder.newFolder("atomic_test")
        val file = File(folder, "data.txt")
        val atomicFile = SafeAtomicFile(file)

        // Initial write
        val out1 = atomicFile.startWrite()
        out1.write("committed state 1".toByteArray())
        atomicFile.finishWrite(out1)
        assertEquals("committed state 1", atomicFile.openRead().bufferedReader().readText())

        // Simulate crash during write: startWrite creates .bak, write partial, failWrite called
        val out2 = atomicFile.startWrite()
        out2.write("partial broken write".toByteArray())
        atomicFile.failWrite(out2)

        // After failure, SafeAtomicFile must restore previous committed state 1
        assertEquals("committed state 1", atomicFile.openRead().bufferedReader().readText())
    }
}

