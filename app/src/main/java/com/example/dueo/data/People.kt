package com.example.dueo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
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
 * Robust atomic file persistence.
 * Implements write-ahead backup file (.bak) semantics with fsync,
 * working identically in Android runtime and JVM unit testing.
 */
class SafeAtomicFile(val baseFile: File) {
    val backupFile = File(baseFile.path + ".bak")

    fun exists(): Boolean = baseFile.exists() || backupFile.exists()

    fun openRead(): InputStream {
        if (backupFile.exists()) {
            baseFile.delete()
            backupFile.renameTo(baseFile)
        }
        return baseFile.inputStream()
    }

    fun startWrite(): OutputStream {
        val parent = baseFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        if (baseFile.exists()) {
            if (!backupFile.exists()) {
                if (!baseFile.renameTo(backupFile)) {
                    baseFile.copyTo(backupFile, overwrite = true)
                    baseFile.delete()
                }
            } else {
                baseFile.delete()
            }
        }

        return try {
            FileOutputStream(baseFile)
        } catch (e: Exception) {
            backupFile.copyTo(baseFile, overwrite = true)
            throw e
        }
    }

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
        backupFile.delete()
    }

    fun failWrite(stream: OutputStream) {
        try {
            stream.flush()
        } catch (_: Exception) {}
        try {
            stream.close()
        } catch (_: Exception) {}
        baseFile.delete()
        if (backupFile.exists()) {
            backupFile.renameTo(baseFile)
        }
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
            return loadLegacyOrEmpty()
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

        return Entry(
            id = row.getString("id"),
            person = row.getString("person"),
            amount = row.getLong("amount"),
            kind = EntryKind.valueOf(row.getString("kind")),
            note = row.optString("note", ""),
            date = row.getLong("date"),
            personId = row.optString("personId", ""),
            dueDate = dueDate
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
