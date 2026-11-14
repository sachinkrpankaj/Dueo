package com.example.dueo.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.dueo.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

class DueoViewModel(application: Application) : AndroidViewModel(application) {

    private val store = LedgerStore(application.filesDir, application.cacheDir)
    private val repository = LedgerRepository(store)
    private val preferences = ThemePreferences(application)

    val uiState: StateFlow<LedgerUiState> = repository.uiState
    val themeMode: StateFlow<ThemeMode> = preferences.mode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ThemeMode.SYSTEM
    )

    var isSavingTransaction by mutableStateOf(false)
        private set

    var isSavingPerson by mutableStateOf(false)
        private set

    var personError by mutableStateOf<String?>(null)

    // State for backup restoration preview
    var pendingRestorePreview by mutableStateOf<BackupPreview?>(null)
        private set

    var isInspectingBackup by mutableStateOf(false)
        private set

    var isRestoringBackup by mutableStateOf(false)
        private set

    var isExportingBackup by mutableStateOf(false)
        private set

    var isGeneratingShare by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            repository.initialize()
            // Clean up any orphaned crop files left from interrupted sessions
            cleanupOrphanedCrops(application)
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            preferences.set(mode)
        }
    }

    fun retryLoad() {
        viewModelScope.launch {
            repository.initialize()
        }
    }

    fun addPerson(
        name: String,
        phone: String,
        photoPath: String?,
        onSuccess: (Person) -> Unit
    ) {
        if (isSavingPerson) return
        isSavingPerson = true
        personError = null

        viewModelScope.launch {
            try {
                val result = repository.addPerson(name, phone, photoPath)
                if (result.isSuccess) {
                    onSuccess(result.getOrThrow())
                } else {
                    personError = result.exceptionOrNull()?.message ?: "Failed to save person"
                }
            } finally {
                isSavingPerson = false
            }
        }
    }

    fun addTransaction(
        personId: String,
        amount: Long,
        kind: EntryKind,
        note: String,
        dueDate: Long? = null,
        clientOperationId: String? = null,
        onResult: (Result<Entry>) -> Unit
    ) {
        if (isSavingTransaction) return
        isSavingTransaction = true

        viewModelScope.launch {
            try {
                val result = repository.addTransaction(
                    personId = personId,
                    amount = amount,
                    kind = kind,
                    note = note,
                    dueDate = dueDate,
                    clientOperationId = clientOperationId
                )
                onResult(result)
            } finally {
                isSavingTransaction = false
            }
        }
    }

    fun updateTransaction(
        entry: Entry,
        onResult: (Result<Entry>) -> Unit
    ) {
        if (isSavingTransaction) return
        isSavingTransaction = true

        viewModelScope.launch {
            try {
                val result = repository.updateTransaction(entry)
                onResult(result)
            } finally {
                isSavingTransaction = false
            }
        }
    }

    fun deleteTransaction(
        entryId: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (isSavingTransaction) return
        isSavingTransaction = true

        viewModelScope.launch {
            try {
                val result = repository.deleteTransaction(entryId)
                onResult(result)
            } finally {
                isSavingTransaction = false
            }
        }
    }

    suspend fun exportJson(): Result<String> {
        return repository.exportLedgerJson()
    }

    /**
     * Inspects a selected backup URI (either .dueobak ZIP or legacy .json)
     * and sets pendingRestorePreview for explicit user review and confirmation.
     */
    fun inspectBackupUri(uri: Uri, onResult: (Result<BackupPreview>) -> Unit) {
        if (isInspectingBackup || isRestoringBackup) return
        isInspectingBackup = true
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val stream = app.contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("Unable to open selected backup file")
                val result = repository.previewBackup(stream, app.cacheDir)
                if (result.isSuccess) {
                    pendingRestorePreview = result.getOrNull()
                }
                onResult(result)
            } catch (t: Throwable) {
                onResult(Result.failure(t))
            } finally {
                isInspectingBackup = false
            }
        }
    }

    /**
     * Confirms the restoration of a validated BackupPreview.
     * Transactionally saves snapshot, replaces data, and updates UI state.
     */
    fun confirmRestore(
        preview: BackupPreview,
        onResult: (Result<LedgerState>) -> Unit
    ) {
        if (isRestoringBackup) return
        isRestoringBackup = true
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val result = repository.executeRestore(preview, app.filesDir)
                if (result.isSuccess) {
                    pendingRestorePreview = null
                }
                onResult(result)
            } finally {
                isRestoringBackup = false
            }
        }
    }

    fun dismissRestorePreview() {
        pendingRestorePreview?.stagingDir?.deleteRecursively()
        pendingRestorePreview = null
    }

    /**
     * Exports a portable, validated ZIP backup (.dueobak) including actual profile photos.
     */
    fun exportBackupZip(
        uri: Uri,
        onResult: (Result<BackupManifest>) -> Unit
    ) {
        if (isExportingBackup) return
        isExportingBackup = true
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val stream = app.contentResolver.openOutputStream(uri)
                    ?: throw IOException("Unable to open output destination for backup")
                val result = repository.exportBackupZip(stream, app.filesDir)
                onResult(result)
            } catch (t: Throwable) {
                onResult(Result.failure(t))
            } finally {
                isExportingBackup = false
            }
        }
    }

    /**
     * Generates a balance image asynchronously off the main thread.
     */
    fun shareBalance(
        person: Person,
        balance: Balance,
        onSuccess: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (isGeneratingShare) return
        isGeneratingShare = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = StatementSharer.generateBalanceImage(getApplication(), person, balance)
                withContext(Dispatchers.Main) { onSuccess(uri) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { onError(t) }
            } finally {
                withContext(Dispatchers.Main) { isGeneratingShare = false }
            }
        }
    }

    /**
     * Generates a full multi-page PDF statement asynchronously off the main thread.
     */
    fun shareStatementPdf(
        person: Person,
        entries: List<Entry>,
        onSuccess: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (isGeneratingShare) return
        isGeneratingShare = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = StatementSharer.generateStatementPdf(getApplication(), person, entries)
                withContext(Dispatchers.Main) { onSuccess(uri) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { onError(t) }
            } finally {
                withContext(Dispatchers.Main) { isGeneratingShare = false }
            }
        }
    }

    /**
     * Generates a statement image asynchronously off the main thread.
     */
    fun shareStatementImage(
        person: Person,
        entries: List<Entry>,
        onSuccess: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (isGeneratingShare) return
        isGeneratingShare = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = StatementSharer.generateStatementImage(getApplication(), person, entries)
                withContext(Dispatchers.Main) { onSuccess(uri) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { onError(t) }
            } finally {
                withContext(Dispatchers.Main) { isGeneratingShare = false }
            }
        }
    }

    fun restoreFromJson(
        rawJson: String,
        onResult: (Result<Int>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val root = JSONObject(rawJson)
                val peopleArray = root.getJSONArray("people")
                val entriesArray = root.getJSONArray("entries")

                val people = List(peopleArray.length()) { i ->
                    val obj = peopleArray.getJSONObject(i)
                    Person(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        phone = obj.optString("phone"),
                        photoPath = obj.optString("photoPath").takeIf { p -> p.isNotBlank() }
                    )
                }

                val entries = List(entriesArray.length()) { i ->
                    store.parseEntry(entriesArray.getJSONObject(i))
                }

                val importedState = LedgerState(people = people, entries = entries)
                val restoreResult = repository.restoreLedger(importedState)
                if (restoreResult.isSuccess) {
                    onResult(Result.success(entries.size))
                } else {
                    onResult(Result.failure(restoreResult.exceptionOrNull() ?: Exception("Restore failed")))
                }
            } catch (t: Throwable) {
                onResult(Result.failure(t))
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return DueoViewModel(application) as T
            }
        }
    }
}
