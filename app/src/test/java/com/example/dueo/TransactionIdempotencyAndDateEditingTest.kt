package com.example.dueo

import com.example.dueo.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransactionIdempotencyAndDateEditingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun duplicateClientOperationIdIsIdempotent() = runBlocking {
        val filesDir = tempFolder.newFolder("files_idempotent")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()
        val person = repository.addPerson("Manish", "999", null).getOrThrow()

        val opId = "unique-op-key-12345"

        // First call
        val firstResult = repository.addTransaction(
            personId = person.id,
            amount = 100000L,
            kind = EntryKind.LENT,
            note = "Camera purchase",
            clientOperationId = opId
        )
        assertTrue(firstResult.isSuccess)
        val firstEntry = firstResult.getOrThrow()

        // Second call with same clientOperationId
        val secondResult = repository.addTransaction(
            personId = person.id,
            amount = 100000L,
            kind = EntryKind.LENT,
            note = "Camera purchase",
            clientOperationId = opId
        )
        assertTrue(secondResult.isSuccess)
        val secondEntry = secondResult.getOrThrow()

        // Must return identical entry
        assertEquals(firstEntry.id, secondEntry.id)

        // Ledger must have exactly 1 entry, not 2
        val state = repository.getCurrentState()
        assertNotNull(state)
        assertEquals(1, state!!.entries.size)
        assertEquals(100000L, state.entries[0].amount)
    }

    @Test
    fun duplicateClientOperationIdPersistsAcrossAppRestarts() = runBlocking {
        val filesDir = tempFolder.newFolder("files_idempotent_restart")
        val store1 = LedgerStore(filesDir = filesDir)
        val repository1 = LedgerRepository(store = store1)

        repository1.initialize()
        val person = repository1.addPerson("Manish", "999", null).getOrThrow()

        val opId = "restart-persistent-op-987"

        // First call in session 1
        val firstResult = repository1.addTransaction(
            personId = person.id,
            amount = 100000L,
            kind = EntryKind.LENT,
            note = "Camera purchase",
            clientOperationId = opId
        )
        assertTrue(firstResult.isSuccess)
        val firstEntry = firstResult.getOrThrow()

        // Simulate app kill and restart: create fresh store and repository instance reading from disk
        val store2 = LedgerStore(filesDir = filesDir)
        val repository2 = LedgerRepository(store = store2)
        repository2.initialize()

        // Second call with same clientOperationId in session 2
        val secondResult = repository2.addTransaction(
            personId = person.id,
            amount = 100000L,
            kind = EntryKind.LENT,
            note = "Camera purchase",
            clientOperationId = opId
        )
        assertTrue(secondResult.isSuccess)
        val secondEntry = secondResult.getOrThrow()

        assertEquals(firstEntry.id, secondEntry.id)
        val state = repository2.getCurrentState()!!
        assertEquals("Must still have exactly 1 transaction after restart deduplication", 1, state.entries.size)
    }

    @Test
    fun rapidDoubleSubmissionWithoutOpIdIsDeduplicatedWithinTimeWindow() = runBlocking {
        val filesDir = tempFolder.newFolder("files_rapid_double")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()
        val person = repository.addPerson("Anita", "888", null).getOrThrow()

        val time = System.currentTimeMillis()
        val first = repository.addTransaction(person.id, 50000L, EntryKind.LENT, "Dinner", date = time).getOrThrow()
        // Immediate second tap with identical parameters
        val second = repository.addTransaction(person.id, 50000L, EntryKind.LENT, "Dinner", date = time + 100L).getOrThrow()

        assertEquals("Rapid duplicate tap must return existing entry", first.id, second.id)
        assertEquals(1, repository.getCurrentState()!!.entries.size)
    }

    @Test
    fun atomicStorageRecoversWhenBaseFileIsCorruptedOrZeroBytes() {
        val filesDir = tempFolder.newFolder("atomic_crash_recovery")
        val baseFile = File(filesDir, "ledger-v2.json")
        val atomicFile = SafeAtomicFile(baseFile)

        // Write initial valid state
        val initialJson = """{"version":2,"people":[],"entries":[]}"""
        val out = atomicFile.startWrite()
        out.write(initialJson.toByteArray(Charsets.UTF_8))
        atomicFile.finishWrite(out)

        assertTrue(baseFile.exists() && baseFile.length() > 0)

        // Simulate crash mid-write: startWrite creates .bak, but write crashes before finish, leaving 0-byte baseFile
        atomicFile.startWrite()
        assertTrue("Backup file must exist during in-flight write", atomicFile.backupFile.exists())
        assertEquals("Backup file must have original size", initialJson.length.toLong(), atomicFile.backupFile.length())

        // Simulate restart and openRead
        val recoveredStream = atomicFile.openRead()
        val recoveredContent = recoveredStream.bufferedReader().use { it.readText() }
        assertEquals("Atomic file must recover the valid data from .bak", initialJson, recoveredContent)
    }

    @Test
    fun transactionDateEditingMaintainsChronologicalIntegrity() = runBlocking {
        val filesDir = tempFolder.newFolder("files_date_edit")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()
        val person = repository.addPerson("Priya", "777", null).getOrThrow()

        // Loan on day 10 (t = 10000)
        val loan = repository.addTransaction(
            personId = person.id,
            amount = 50000L,
            kind = EntryKind.LENT,
            note = "Loan",
            date = 10000L
        ).getOrThrow()

        // Repayment on day 20 (t = 20000)
        val repayment = repository.addTransaction(
            personId = person.id,
            amount = 30000L,
            kind = EntryKind.RECEIVED,
            note = "Part payback",
            date = 20000L
        ).getOrThrow()

        // Valid edit: move repayment to day 15 (t = 15000) -> loan still precedes repayment
        val validEdit = repayment.copy(date = 15000L, dueDate = 25000L)
        val updateResult = repository.updateTransaction(validEdit)
        assertTrue(updateResult.isSuccess)
        assertEquals(15000L, updateResult.getOrThrow().date)
        assertEquals(25000L, updateResult.getOrThrow().dueDate)

        // Invalid edit: move repayment to day 5 (t = 5000) -> precedes loan, causing over-repayment!
        val invalidEdit = repayment.copy(date = 5000L)
        val failedResult = repository.updateTransaction(invalidEdit)
        assertTrue("Moving repayment before loan must fail", failedResult.isFailure)
        assertTrue(failedResult.exceptionOrNull() is OverRepaymentException)

        // State remains in valid state (repayment still on day 15)
        val state = repository.getCurrentState()!!
        val currentRepayment = state.entries.first { it.id == repayment.id }
        assertEquals(15000L, currentRepayment.date)
    }

    @Test
    fun cropCleanupRejectsPathsOutsideCacheDir() {
        val cacheDir = tempFolder.newFolder("cache_cleanup")
        val outsideDir = tempFolder.newFolder("outside_cleanup")

        val validCropFile = File(cacheDir, "person-crop-valid.jpg").apply { writeText("temp crop") }
        val nonCropFile = File(cacheDir, "normal-file.jpg").apply { writeText("normal file") }
        val outsideFile = File(outsideDir, "person-crop-outside.jpg").apply { writeText("outside file") }

        assertTrue(validCropFile.exists())
        assertTrue(nonCropFile.exists())
        assertTrue(outsideFile.exists())

        // 1. Valid crop file in cacheDir is deleted
        cleanupCropFile(cacheDir, validCropFile.absolutePath)
        assertFalse(validCropFile.exists())

        // 2. Non-crop file in cacheDir is NOT deleted
        cleanupCropFile(cacheDir, nonCropFile.absolutePath)
        assertTrue(nonCropFile.exists())

        // 3. Outside file is rejected by canonical cacheDir check and NOT deleted
        cleanupCropFile(cacheDir, outsideFile.absolutePath)
        assertTrue(outsideFile.exists())
    }
}
