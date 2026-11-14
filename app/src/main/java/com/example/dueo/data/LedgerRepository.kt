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
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

    // LRU cache of recent client operations for idempotency
    private val recentOperations = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > 100
        }
    }

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
     * Supports an optional clientOperationId for idempotency to protect against double submissions.
     */
    suspend fun addTransaction(
        personId: String,
        amount: Long,
        kind: EntryKind,
        note: String,
        dueDate: Long? = null,
        date: Long = System.currentTimeMillis(),
        clientOperationId: String? = null
    ): Result<Entry> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                if (!clientOperationId.isNullOrBlank()) {
                    val cached = recentOperations[clientOperationId]
                    if (cached != null) {
                        return@withContext Result.success(cached)
                    }
                }

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
                if (!clientOperationId.isNullOrBlank()) {
                    recentOperations[clientOperationId] = newEntry
                }
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
     * Inspects a backup stream (ZIP or JSON) and returns a detailed preview
     * without altering current state.
     */
    suspend fun previewBackup(inputStream: InputStream, stagingParentDir: File): Result<BackupPreview> = withContext(ioDispatcher) {
        try {
            val preview = BackupManager.inspectBackup(inputStream, stagingParentDir)
            Result.success(preview)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Executes a transactional restore from a validated BackupPreview.
     * Takes an automatic pre-restore safety snapshot of the existing valid ledger,
     * extracts profile photos safely to filesDir/people-photos, writes and verifies the state.
     * If any failure occurs, original records are kept intact.
     */
    suspend fun executeRestore(preview: BackupPreview, filesDir: File): Result<LedgerState> = mutex.withLock {
        withContext(ioDispatcher) {
            var snapshotCreated: File? = null
            try {
                // 1. Create safety snapshot of current valid ledger
                snapshotCreated = BackupManager.createPreRestoreSnapshot(filesDir)

                // 2. Prepare photos directory
                val photosFolder = File(filesDir, "people-photos").apply { mkdirs() }
                val updatedPeople = preview.validatedState.people.map { person ->
                    val extractedPhoto = preview.extractedPhotos[person.id]
                    if (extractedPhoto != null && extractedPhoto.exists()) {
                        val destFile = File(photosFolder, "${person.id}.jpg")
                        extractedPhoto.copyTo(destFile, overwrite = true)
                        person.copy(photoPath = destFile.absolutePath)
                    } else {
                        // For legacy JSON or absent photos, retain existing local file if valid, else null
                        val existingLocal = if (!person.photoPath.isNullOrBlank() && File(person.photoPath).exists()) {
                            person.photoPath
                        } else null
                        person.copy(photoPath = existingLocal)
                    }
                }

                val finalState = preview.validatedState.copy(people = updatedPeople)
                validator.validateLedger(finalState)

                // 3. Atomically write the restored state
                store.writeState(finalState)

                // 4. Verify read back
                val readBack = store.readState()
                if (readBack.entries.size != finalState.entries.size || readBack.people.size != finalState.people.size) {
                    throw IllegalStateException("Verification failed: written records do not match imported backup")
                }

                // 5. Clean up temporary staging
                preview.stagingDir?.deleteRecursively()

                _uiState.value = LedgerUiState.Ready(finalState)
                Result.success(finalState)
            } catch (t: Throwable) {
                // Rollback if snapshot was created and state was corrupted
                if (snapshotCreated != null && snapshotCreated.exists()) {
                    runCatching {
                        snapshotCreated.copyTo(store.ledgerFile, overwrite = true)
                    }
                }
                preview.stagingDir?.deleteRecursively()
                Result.failure(t)
            }
        }
    }

    /**
     * Restores an imported ledger backup safely after complete validation.
     * Automatically creates a pre-restore safety snapshot before writing.
     */
    suspend fun restoreLedger(imported: LedgerState): Result<Unit> = mutex.withLock {
        withContext(ioDispatcher) {
            var snapshot: File? = null
            try {
                validator.validateLedger(imported)
                snapshot = BackupManager.createPreRestoreSnapshot(store.filesDir)
                store.writeState(imported)
                _uiState.value = LedgerUiState.Ready(imported)
                Result.success(Unit)
            } catch (t: Throwable) {
                if (snapshot != null && snapshot.exists()) {
                    runCatching { snapshot.copyTo(store.ledgerFile, overwrite = true) }
                }
                Result.failure(t)
            }
        }
    }

    /**
     * Exports a portable, versioned ZIP backup (.dueobak) including actual profile photos.
     */
    suspend fun exportBackupZip(outputStream: OutputStream, filesDir: File): Result<BackupManifest> = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                val currentLedger = when (val state = _uiState.value) {
                    is LedgerUiState.Ready -> state.ledger
                    else -> store.readState()
                }
                val manifest = BackupManager.exportBackupZip(currentLedger, outputStream, filesDir)
                Result.success(manifest)
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

