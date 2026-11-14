package com.example.dueo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class Person(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String = "",
    val photoPath: String? = null
)

data class LedgerState(
    val people: List<Person> = emptyList(),
    val entries: List<Entry> = emptyList()
)

/**
 * Robust atomic file persistence implementing Android AtomicFile design.
 * Guarantees crash consistency: an interrupted write never destroys the last committed file.
 * Automatically recovers from backup (.bak) on crash, partial write, or corruption.
 */
class SafeAtomicFile(val baseFile: File) {
    val backupFile = File(baseFile.path + ".bak")

    fun exists(): Boolean = (baseFile.exists() && baseFile.length() > 0L) || (backupFile.exists() && backupFile.length() > 0L)

    /**
     * Opens the file for reading. If a backup file exists, an interrupted write occurred,
     * so baseFile is discarded and backupFile is restored to baseFile.
     */
    fun openRead(): InputStream {
        if (backupFile.exists() && backupFile.length() > 0L) {
            // Write was interrupted before completion. Discard partial/uncommitted baseFile and restore backupFile.
            baseFile.delete()
            if (!backupFile.renameTo(baseFile)) {
                if (!tryCopy(backupFile, baseFile)) {
                    // If copy also fails, read directly from backupFile to avoid deleting/losing valid data!
                    return backupFile.inputStream()
                }
                backupFile.delete()
            }
        }
        if (baseFile.exists()) {
            return baseFile.inputStream()
        }
        if (backupFile.exists()) {
            return backupFile.inputStream()
        }
        throw FileNotFoundException("File does not exist: ${baseFile.path}")
    }

    /**
     * Starts a new write. If baseFile exists, it is renamed to backupFile so the previous
     * committed state is preserved during the write.
     */
    fun startWrite(): OutputStream {
        val parent = baseFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        if (backupFile.exists()) {
            // A previous write was interrupted. Delete baseFile (the partial file) and preserve backupFile
            // so that if this write also crashes, backupFile still holds the last good committed state.
            baseFile.delete()
        } else if (baseFile.exists()) {
            if (!baseFile.renameTo(backupFile)) {
                if (!tryCopy(baseFile, backupFile)) {
                    throw IOException("Failed to create backup file before writing: ${backupFile.path}")
                }
                baseFile.delete()
            }
        }

        return try {
            FileOutputStream(baseFile)
        } catch (e: Exception) {
            // If opening stream fails, attempt to restore backup
            if (backupFile.exists() && !baseFile.exists()) {
                backupFile.renameTo(baseFile)
            }
            throw e
        }
    }

    /**
     * Successfully finishes a write. Flushes, syncs to disk (fsync), closes the stream,
     * and deletes backupFile ONLY AFTER verifying that baseFile was successfully written and non-empty.
     */
    fun finishWrite(stream: OutputStream) {
        try {
            stream.flush()
            if (stream is FileOutputStream) {
                try {
                    stream.fd.sync()
                } catch (_: Exception) {}
            }
        } finally {
            stream.close()
        }

        // Never delete a valid backup before verifying that the base file exists and is non-empty
        if (baseFile.exists() && baseFile.length() > 0L) {
            backupFile.delete()
        }
    }

    /**
     * Fails a write. Discards partial baseFile and restores backupFile to baseFile.
     */
    fun failWrite(stream: OutputStream) {
        try {
            stream.flush()
        } catch (_: Exception) {}
        try {
            stream.close()
        } catch (_: Exception) {}

        baseFile.delete()
        if (backupFile.exists() && backupFile.length() > 0L) {
            if (!backupFile.renameTo(baseFile)) {
                tryCopy(backupFile, baseFile)
            }
        }
    }

    private fun tryCopy(source: File, dest: File): Boolean {
        return runCatching {
            source.copyTo(dest, overwrite = true)
            dest.exists() && dest.length() == source.length()
        }.getOrDefault(false)
    }
}

/**
 * Structured loading results that distinguish missing files, malformed JSON,
 * unsupported schema versions, and successful loads without silently discarding user records.
 */
sealed interface LedgerLoadResult {
    data class Success(val state: LedgerState) : LedgerLoadResult
    object Empty : LedgerLoadResult
    data class RecoverableFailure(
        val message: String,
        val backupFile: File?,
        val rawContent: String?,
        val cause: Throwable
    ) : LedgerLoadResult
    data class UnsupportedVersion(
        val version: Int,
        val message: String,
        val backupFile: File?
    ) : LedgerLoadResult
    data class UnrecoverableFailure(
        val message: String,
        val cause: Throwable
    ) : LedgerLoadResult
}

/**
 * Atomic, persistent ledger storage.
 * Enforces financial domain validation before saving, detects corruption,
 * and preserves corrupted files for recovery rather than wiping them out.
 */
class LedgerStore(
    val filesDir: File,
    val cacheDir: File = filesDir
) {
    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    val ledgerFile = File(filesDir, "ledger-v2.json")
    val legacyFile = File(filesDir, "ledger-v1.json")
    val atomicFile = SafeAtomicFile(ledgerFile)

    /**
     * Inspects and loads the persistent ledger with structured error classification.
     */
    fun loadLedger(): LedgerLoadResult {
        if (!atomicFile.exists()) {
            return loadLegacyOrEmpty()
        }

        val rawText: String
        try {
            rawText = atomicFile.openRead().bufferedReader().use { it.readText() }
        } catch (fnf: FileNotFoundException) {
            val backup = preserveCorruptedFile(ledgerFile, "missing_after_check")
            return LedgerLoadResult.RecoverableFailure(
                message = "Ledger file exists but could not be opened: ${fnf.message}",
                backupFile = backup,
                rawContent = null,
                cause = fnf
            )
        } catch (ioe: Exception) {
            val backup = preserveCorruptedFile(ledgerFile, "read_error")
            return LedgerLoadResult.RecoverableFailure(
                message = "Unable to read ledger file from disk: ${ioe.message}",
                backupFile = backup,
                rawContent = null,
                cause = ioe
            )
        }

        if (rawText.trim().isEmpty()) {
            // Check if backupFile exists and has valid data
            if (atomicFile.backupFile.exists() && atomicFile.backupFile.length() > 0L) {
                try {
                    val recoveredText = atomicFile.backupFile.bufferedReader().use { it.readText() }
                    if (recoveredText.trim().isNotEmpty()) {
                        atomicFile.backupFile.copyTo(atomicFile.baseFile, overwrite = true)
                        return loadLedger()
                    }
                } catch (_: Exception) {}
            }
            val backup = preserveCorruptedFile(ledgerFile, "empty_file")
            return LedgerLoadResult.RecoverableFailure(
                message = "Ledger file exists but is unexpectedly empty",
                backupFile = backup,
                rawContent = rawText,
                cause = IllegalStateException("Empty ledger file")
            )
        }

        val root: JSONObject
        try {
            root = JSONObject(rawText)
        } catch (jsonEx: JSONException) {
            // Check if backup file is available and valid before declaring failure
            if (atomicFile.backupFile.exists() && atomicFile.backupFile.length() > 0L) {
                try {
                    val bakText = atomicFile.backupFile.bufferedReader().use { it.readText() }
                    val bakRoot = JSONObject(bakText)
                    if (bakRoot.optInt("version") == 2) {
                        atomicFile.backupFile.copyTo(atomicFile.baseFile, overwrite = true)
                        return loadLedger()
                    }
                } catch (_: Exception) {}
            }
            val backup = preserveCorruptedFile(ledgerFile, "malformed_json")
            return LedgerLoadResult.RecoverableFailure(
                message = "Ledger contains malformed JSON",
                backupFile = backup,
                rawContent = rawText,
                cause = jsonEx
            )
        }

        val version = root.optInt("version", -1)
        if (version != 2) {
            val backup = preserveCorruptedFile(ledgerFile, "unsupported_v$version")
            return LedgerLoadResult.UnsupportedVersion(
                version = version,
                message = "Ledger schema version $version is not supported by this app version",
                backupFile = backup
            )
        }

        val parsedState: LedgerState
        try {
            val peopleArray = root.getJSONArray("people")
            val entriesArray = root.getJSONArray("entries")

            val people = List(peopleArray.length()) { i ->
                val it = peopleArray.getJSONObject(i)
                Person(
                    id = it.getString("id"),
                    name = it.getString("name"),
                    phone = it.optString("phone"),
                    photoPath = it.optString("photoPath").takeIf { p -> p.isNotBlank() }
                )
            }

            val entries = List(entriesArray.length()) { i ->
                parseEntry(entriesArray.getJSONObject(i))
            }

            parsedState = LedgerState(people = people, entries = entries)
        } catch (ex: Exception) {
            val backup = preserveCorruptedFile(ledgerFile, "parse_error")
            return LedgerLoadResult.RecoverableFailure(
                message = "Error deserializing ledger structure: ${ex.message}",
                backupFile = backup,
                rawContent = rawText,
                cause = ex
            )
        }

        // Validate financial invariants using centralized validator
        try {
            FinancialValidator.validateLedger(parsedState)
        } catch (valEx: FinancialValidationException) {
            val backup = preserveCorruptedFile(ledgerFile, "invalid_records")
            return LedgerLoadResult.RecoverableFailure(
                message = "Ledger contains invalid financial data: ${valEx.message}",
                backupFile = backup,
                rawContent = rawText,
                cause = valEx
            )
        }

        return LedgerLoadResult.Success(parsedState)
    }

    private fun loadLegacyOrEmpty(): LedgerLoadResult {
        if (!legacyFile.exists()) {
            return LedgerLoadResult.Empty
        }

        val raw: String
        try {
            raw = legacyFile.bufferedReader().use { it.readText() }
        } catch (fnf: FileNotFoundException) {
            return LedgerLoadResult.Empty
        } catch (ioe: Exception) {
            val backup = preserveCorruptedFile(legacyFile, "legacy_read_error")
            return LedgerLoadResult.RecoverableFailure(
                message = "Unable to read legacy ledger-v1.json file",
                backupFile = backup,
                rawContent = null,
                cause = ioe
            )
        }

        if (raw.trim().isEmpty()) {
            return LedgerLoadResult.Empty
        }

        try {
            val rows = JSONArray(raw)
            val legacyEntries = List(rows.length()) { parseEntry(rows.getJSONObject(it)) }
            val migrated = migrateEntries(legacyEntries)
            FinancialValidator.validateLedger(migrated)
            // Persist migrated v2 ledger atomically while leaving legacy-v1 untouched
            writeState(migrated)
            return LedgerLoadResult.Success(migrated)
        } catch (ex: Exception) {
            val backup = preserveCorruptedFile(legacyFile, "legacy_malformed")
            return LedgerLoadResult.RecoverableFailure(
                message = "Legacy ledger-v1.json contains malformed or invalid data: ${ex.message}",
                backupFile = backup,
                rawContent = raw,
                cause = ex
            )
        }
    }

    /**
     * Backward-compatible read method. Returns LedgerState or throws descriptive CorruptedLedgerException.
     */
    fun readState(): LedgerState {
        return when (val result = loadLedger()) {
            is LedgerLoadResult.Success -> result.state
            is LedgerLoadResult.Empty -> LedgerState()
            is LedgerLoadResult.RecoverableFailure -> throw CorruptedLedgerException(result.message, result.cause)
            is LedgerLoadResult.UnsupportedVersion -> throw CorruptedLedgerException(result.message)
            is LedgerLoadResult.UnrecoverableFailure -> throw CorruptedLedgerException(result.message, result.cause)
        }
    }

    fun parseEntry(row: JSONObject): Entry {
        val dueDate = if (row.has("dueDate") && !row.isNull("dueDate")) {
            row.getLong("dueDate").takeIf { it > 0 }
        } else null

        val clientOperationId = if (row.has("clientOperationId") && !row.isNull("clientOperationId")) {
            row.getString("clientOperationId").takeIf { it.isNotBlank() }
        } else null

        return Entry(
            id = row.getString("id"),
            person = row.getString("person"),
            amount = row.getLong("amount"),
            kind = EntryKind.valueOf(row.getString("kind")),
            note = row.optString("note", ""),
            date = row.getLong("date"),
            personId = row.optString("personId", ""),
            dueDate = dueDate,
            clientOperationId = clientOperationId
        )
    }

    /**
     * Serializes and writes state atomically.
     * Guaranteed to independently enforce financial validation prior to writing.
     */
    fun writeState(state: LedgerState) {
        // Enforce centralized financial validation
        FinancialValidator.validateLedger(state)

        val people = JSONArray()
        state.people.forEach { p ->
            people.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name.trim())
                put("phone", p.phone)
                put("photoPath", p.photoPath.orEmpty())
            })
        }

        val entries = JSONArray()
        state.entries.forEach { e ->
            entries.put(JSONObject().apply {
                put("id", e.id)
                put("person", e.person)
                put("personId", e.personId)
                put("amount", e.amount)
                put("kind", e.kind.name)
                put("note", e.note)
                put("date", e.date)
                if (e.dueDate != null) {
                    put("dueDate", e.dueDate)
                }
                if (!e.clientOperationId.isNullOrBlank()) {
                    put("clientOperationId", e.clientOperationId)
                }
            })
        }

        val root = JSONObject().apply {
            put("version", 2)
            put("people", people)
            put("entries", entries)
        }

        val stream = atomicFile.startWrite()
        try {
            stream.write(root.toString(2).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    fun addPerson(state: LedgerState, name: String, phone: String, croppedPath: String?): LedgerState {
        val trimmedName = name.trim()
        val trimmedPhone = phone.trim()
        if (trimmedName.isEmpty()) throw BlankPersonNameException("Person name cannot be blank")

        val id = UUID.randomUUID().toString()
        val photo = croppedPath?.let { source ->
            val folder = File(filesDir, "people-photos").apply { mkdirs() }
            val dest = File(folder, "$id.jpg")
            val srcFile = File(source)
            if (srcFile.exists()) {
                srcFile.copyTo(dest, overwrite = true)
                // Controlled cleanup of temporary crop file from cache
                if (srcFile.parentFile == cacheDir || srcFile.name.startsWith("person-crop-")) {
                    srcFile.delete()
                }
            }
            dest
        }

        val newPerson = Person(
            id = id,
            name = trimmedName,
            phone = trimmedPhone,
            photoPath = photo?.absolutePath
        )

        val updated = state.copy(people = state.people + newPerson)
        try {
            writeState(updated)
        } catch (error: Exception) {
            photo?.delete()
            throw error
        }
        return updated
    }

    /**
     * Preserves a corrupted or invalid file with a unique timestamped recovery name.
     */
    fun preserveCorruptedFile(original: File, reasonTag: String): File? {
        return try {
            if (!original.exists()) return null
            val recoveryDir = File(filesDir, "corrupted_backups").apply { mkdirs() }
            val backupFile = File(recoveryDir, "${original.name}.$reasonTag.${System.currentTimeMillis()}.bak")
            original.copyTo(backupFile, overwrite = true)
            backupFile
        } catch (_: Exception) {
            null
        }
    }
}

fun migrateEntries(entries: List<Entry>): LedgerState {
    val people = entries.map { it.person.trim() }.distinct().map { Person(name = it) }
    return LedgerState(
        people = people,
        entries = entries.map { e ->
            val matching = people.first { it.name == e.person.trim() }
            e.copy(person = matching.name, personId = matching.id)
        }
    )
}
