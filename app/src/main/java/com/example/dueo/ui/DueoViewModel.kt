package com.example.dueo.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dueo.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
                    dueDate = dueDate
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
}
