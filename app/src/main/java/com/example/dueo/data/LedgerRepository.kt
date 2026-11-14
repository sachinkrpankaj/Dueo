package com.example.dueo.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface LedgerUiState {
    object Loading : LedgerUiState
    data class Ready(val ledger: LedgerState) : LedgerUiState
    data class Corrupted(
        val message: String,
        val backupPath: String?,
        val rawContent: String?,
        val cause: Throwable
    ) : LedgerUiState
    data class Unsupported(
        val version: Int,
        val message: String,
        val backupPath: String?
    ) : LedgerUiState
    data class Error(val message: String, val cause: Throwable? = null) : LedgerUiState
}

/**
 * Centralized, concurrency-safe repository that owns the authoritative ledger state.
 * Uses a Mutex to serialize all mutations and ensure writes operate against
 * the latest committed state without race conditions or lost snapshots.
 */
class LedgerRepository(
    private val store: LedgerStore,
    private val validator: FinancialValidator = FinancialValidator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private val _uiState = MutableStateFlow<LedgerUiState>(LedgerUiState.Loading)
    val uiState: StateFlow<LedgerUiState> = _uiState.asStateFlow()

    suspend fun initialize(): LedgerUiState = mutex.withLock {
        withContext(ioDispatcher) {
            val result = store.loadLedger()
            val state = when (result) {
                is LedgerLoadResult.Success -> LedgerUiState.Ready(result.state)
                is LedgerLoadResult.Empty -> {
                    val emptyState = LedgerState()
                    LedgerUiState.Ready(emptyState)
                }
                is LedgerLoadResult.RecoverableFailure -> LedgerUiState.Corrupted(
                    message = result.message,
                    backupPath = result.backupFile?.absolutePath,
                    rawContent = result.rawContent,
                    cause = result.cause
                )
                is LedgerLoadResult.UnsupportedVersion -> LedgerUiState.Unsupported(
                    version = result.version,
                    message = result.message,
                    backupPath = result.backupFile?.absolutePath
                )
                is LedgerLoadResult.UnrecoverableFailure -> LedgerUiState.Error(
                    message = result.message,
                    cause = result.cause
                )
            }
            _uiState.value = state
            state
        }
    }

    suspend fun getCurrentState(): LedgerState? {
        return when (val current = _uiState.value) {
            is LedgerUiState.Ready -> current.ledger
            else -> null
        }
    }

    /**
     * Adds a new person atomically.
     */
    suspend fun addPerson(name: String, phone: String, croppedPath: String?): Result<Person> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                val currentLedger = when (val state = _uiState.value) {
                    is LedgerUiState.Ready -> state.ledger
                    else -> store.readState()
                }

                val trimmedName = name.trim()
                val trimmedPhone = phone.trim()
                if (trimmedName.isEmpty()) {
                    return@withContext Result.failure(BlankPersonNameException("Person name cannot be blank"))
                }

                val updated = store.addPerson(currentLedger, trimmedName, trimmedPhone, croppedPath)
                _uiState.value = LedgerUiState.Ready(updated)
                Result.success(updated.people.last())
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Adds a new transaction atomically after independent financial validation.
     */
    suspend fun addTransaction(
        personId: String,
        amount: Long,
        kind: EntryKind,
        note: String,
        dueDate: Long? = null,
        date: Long = System.currentTimeMillis()
    ): Result<Entry> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                val currentLedger = when (val state = _uiState.value) {
                    is LedgerUiState.Ready -> state.ledger
                    else -> store.readState()
                }

                val person = currentLedger.people.firstOrNull { it.id == personId }
                    ?: return@withContext Result.failure(PersonNotFoundException(personId))

                val newEntry = Entry(
                    id = UUID.randomUUID().toString(),
                    person = person.name,
                    personId = person.id,
                    amount = amount,
                    kind = kind,
                    note = note.trim(),
                    date = date,
                    dueDate = dueDate
                )

                // Enforce domain validation on prospective state
                validator.validateProspectiveEntry(currentLedger, newEntry)

                val updatedLedger = currentLedger.copy(entries = currentLedger.entries + newEntry)
                store.writeState(updatedLedger)
                _uiState.value = LedgerUiState.Ready(updatedLedger)
                Result.success(newEntry)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Updates an existing transaction (amount, note, date, dueDate).
     * Re-validates the resulting ledger chronologically to prevent illegal states
     * (such as reducing loan amount below repayments already received).
     */
    suspend fun updateTransaction(updatedEntry: Entry): Result<Entry> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                val currentLedger = when (val state = _uiState.value) {
                    is LedgerUiState.Ready -> state.ledger
                    else -> store.readState()
                }

                val existingIndex = currentLedger.entries.indexOfFirst { it.id == updatedEntry.id }
                if (existingIndex < 0) {
                    return@withContext Result.failure(IllegalArgumentException("Transaction not found: ${updatedEntry.id}"))
                }

                val person = currentLedger.people.firstOrNull { it.id == updatedEntry.personId }
                    ?: return@withContext Result.failure(PersonNotFoundException(updatedEntry.personId))

                val verifiedEntry = updatedEntry.copy(person = person.name)
                validator.validateEntryBasic(verifiedEntry)

                val prospectiveEntries = currentLedger.entries.toMutableList()
                prospectiveEntries[existingIndex] = verifiedEntry

                val prospectiveLedger = currentLedger.copy(entries = prospectiveEntries)
                validator.validateLedger(prospectiveLedger)

                store.writeState(prospectiveLedger)
                _uiState.value = LedgerUiState.Ready(prospectiveLedger)
                Result.success(verifiedEntry)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Deletes an existing transaction.
     * Prevents deletion if removing this entry would cause subsequent repayments to exceed obligations.
     */
    suspend fun deleteTransaction(entryId: String): Result<Unit> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                val currentLedger = when (val state = _uiState.value) {
                    is LedgerUiState.Ready -> state.ledger
                    else -> store.readState()
                }

                val entryToDelete = currentLedger.entries.firstOrNull { it.id == entryId }
                    ?: return@withContext Result.failure(IllegalArgumentException("Transaction not found: $entryId"))

                val prospectiveEntries = currentLedger.entries.filterNot { it.id == entryId }
                val prospectiveLedger = currentLedger.copy(entries = prospectiveEntries)

                // Validate chronological integrity after deletion
                validator.validateLedger(prospectiveLedger)

                store.writeState(prospectiveLedger)
                _uiState.value = LedgerUiState.Ready(prospectiveLedger)
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Restores an imported ledger backup safely after complete validation.
     */
    suspend fun restoreLedger(imported: LedgerState): Result<Unit> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                validator.validateLedger(imported)
                store.writeState(imported)
                _uiState.value = LedgerUiState.Ready(imported)
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Generates a portable, validated JSON backup of all people and transactions.
     */
    suspend fun exportLedgerJson(): Result<String> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                val currentLedger = when (val state = _uiState.value) {
                    is LedgerUiState.Ready -> state.ledger
                    else -> store.readState()
                }

                val people = JSONArray()
                currentLedger.people.forEach { p ->
                    people.put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("phone", p.phone)
                        put("photoPath", p.photoPath.orEmpty())
                    })
                }

                val entries = JSONArray()
                currentLedger.entries.forEach { e ->
                    entries.put(JSONObject().apply {
                        put("id", e.id)
                        put("person", e.person)
                        put("personId", e.personId)
                        put("amount", e.amount)
                        put("kind", e.kind.name)
                        put("note", e.note)
                        put("date", e.date)
                        if (e.dueDate != null) put("dueDate", e.dueDate)
                    })
                }

                val exportObj = JSONObject().apply {
                    put("app", "Dueo")
                    put("backupSchemaVersion", 1)
                    put("exportedAt", System.currentTimeMillis())
                    put("people", people)
                    put("entries", entries)
                }

                Result.success(exportObj.toString(2))
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }
}
