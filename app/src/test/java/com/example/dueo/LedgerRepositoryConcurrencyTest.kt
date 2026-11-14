package com.example.dueo

import com.example.dueo.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LedgerRepositoryConcurrencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun concurrentTransactionsBothPersistWithoutLostUpdates() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store, ioDispatcher = Dispatchers.Default)

        repository.initialize()
        val person = repository.addPerson("Vikram", "9998887770", null).getOrThrow()

        // Launch 10 concurrent lending transactions
        val jobs = (1..10).map { i ->
            async(Dispatchers.Default) {
                repository.addTransaction(
                    personId = person.id,
                    amount = 1000L * i,
                    kind = EntryKind.LENT,
                    note = "Tx $i"
                )
            }
        }

        val results = jobs.awaitAll()
        assertTrue(results.all { it.isSuccess })

        val state = repository.getCurrentState()
        assertNotNull(state)
        assertEquals(10, state!!.entries.size)
        // Expected total = 1000 * (10 * 11 / 2) = 55,000 paise
        assertEquals(55000L, state.entries.balance().receive)
    }

    @Test
    fun addingPersonConcurrentlyWithTransactionDoesNotDropData() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store, ioDispatcher = Dispatchers.Default)

        repository.initialize()
        val p1 = repository.addPerson("Karan", "1111111111", null).getOrThrow()

        val addPersonJob = async(Dispatchers.Default) {
            repository.addPerson("Simran", "2222222222", null)
        }
        val addTxJob = async(Dispatchers.Default) {
            repository.addTransaction(
                personId = p1.id,
                amount = 25000L,
                kind = EntryKind.LENT,
                note = "Initial loan"
            )
        }

        val p2Result = addPersonJob.await()
        val txResult = addTxJob.await()

        assertTrue(p2Result.isSuccess)
        assertTrue(txResult.isSuccess)

        val state = repository.getCurrentState()
        assertNotNull(state)
        assertEquals(2, state!!.people.size)
        assertEquals(1, state.entries.size)
        assertEquals(25000L, state.entries.single().amount)
    }

    @Test
    fun failedValidationDoesNotCorruptAuthoritativeState() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store, ioDispatcher = Dispatchers.Default)

        repository.initialize()
        val person = repository.addPerson("Ananya", "3333333333", null).getOrThrow()

        // Valid loan of ₹500 (50000 paise)
        repository.addTransaction(person.id, 50000L, EntryKind.LENT, "Initial").getOrThrow()

        // Attempt invalid repayment of ₹600 (60000 paise)
        val failureResult = repository.addTransaction(person.id, 60000L, EntryKind.RECEIVED, "Excess repayment")
        assertTrue(failureResult.isFailure)
        assertTrue(failureResult.exceptionOrNull() is OverRepaymentException)

        // State must remain at ₹500 loan with only 1 entry
        val state = repository.getCurrentState()
        assertNotNull(state)
        assertEquals(1, state!!.entries.size)
        assertEquals(50000L, state.entries.balance().receive)
    }

    @Test
    fun updatingTransactionPreventsIllegalState() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store, ioDispatcher = Dispatchers.Default)

        repository.initialize()
        val person = repository.addPerson("Rohan", "4444444444", null).getOrThrow()

        // Lend ₹1,000 (100000 paise)
        val loan = repository.addTransaction(person.id, 100000L, EntryKind.LENT, "Loan").getOrThrow()
        // Receive ₹600 (60000 paise)
        repository.addTransaction(person.id, 60000L, EntryKind.RECEIVED, "Repayment").getOrThrow()

        // Attempt to edit loan down to ₹500 (50000 paise), which is LESS than the ₹600 already received
        val illegalUpdate = loan.copy(amount = 50000L)
        val updateResult = repository.updateTransaction(illegalUpdate)

        assertTrue(updateResult.isFailure)
        assertTrue(updateResult.exceptionOrNull() is OverRepaymentException)

        // Verify uncorrupted state: original ₹1,000 loan preserved
        val state = repository.getCurrentState()
        assertNotNull(state)
        assertEquals(100000L, state!!.entries.first { it.id == loan.id }.amount)
        assertEquals(40000L, state.entries.balance().receive)
    }

    @Test
    fun deletingTransactionPreventsIllegalNegativeObligation() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val store = LedgerStore(filesDir = filesDir)
        val repository = LedgerRepository(store = store, ioDispatcher = Dispatchers.Default)

        repository.initialize()
        val person = repository.addPerson("Meera", "5555555555", null).getOrThrow()

        val loan = repository.addTransaction(person.id, 50000L, EntryKind.LENT, "Loan").getOrThrow()
        repository.addTransaction(person.id, 20000L, EntryKind.RECEIVED, "Repayment").getOrThrow()

        // Attempt to delete loan entry while repayment of ₹200 exists
        val deleteResult = repository.deleteTransaction(loan.id)
        assertTrue(deleteResult.isFailure)
        assertTrue(deleteResult.exceptionOrNull() is OverRepaymentException)

        // Loan must still exist
        val state = repository.getCurrentState()
        assertNotNull(state)
        assertEquals(2, state!!.entries.size)
    }
}
