package com.example.dueo.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupManifest(
    val app: String = "Dueo",
    val backupSchemaVersion: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val peopleCount: Int = 0,
    val entriesCount: Int = 0,
    val photosCount: Int = 0
)

data class BackupPreview(
    val app: String,
    val schemaVersion: Int,
    val exportedAt: Long,
    val peopleCount: Int,
    val entriesCount: Int,
    val photosCount: Int,
    val earliestDate: Long?,
    val latestDate: Long?,
    val totalReceive: Long,
    val totalPay: Long,
    val isZipArchive: Boolean,
    val validatedState: LedgerState,
    val extractedPhotos: Map<String, File> = emptyMap(),
    val stagingDir: File? = null
)

class InvalidBackupException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object BackupManager {

    private const val MAX_UNCOMPRESSED_BYTES = 100L * 1024L * 1024L // 100 MB safety limit against zip bombs
    private const val MAX_ENTRIES_COUNT = 2000

    /**
     * Exports a portable, versioned ZIP backup (.dueobak) containing:
     * - manifest.json (metadata, counts, schema version)
     * - ledger.json (people and transaction entries with relative photo paths)
     * - images/ (actual profile photo files)
     */
    fun exportBackupZip(
        state: LedgerState,
        outputStream: OutputStream,
        filesDir: File
    ): BackupManifest {
        // Enforce full financial validation before export
        FinancialValidator.validateLedger(state)

        val peopleWithPhotos = state.people.filter { person ->
            !person.photoPath.isNullOrBlank() && File(person.photoPath).exists()
        }

        val manifest = BackupManifest(
            app = "Dueo",
            backupSchemaVersion = 2,
            exportedAt = System.currentTimeMillis(),
            peopleCount = state.people.size,
            entriesCount = state.entries.size,
            photosCount = peopleWithPhotos.size
        )

        // Map state with portable relative photo paths (images/<personId>.jpg)
        val portablePeople = state.people.map { person ->
            val hasPhoto = peopleWithPhotos.any { it.id == person.id }
            val relativePhoto = if (hasPhoto) "images/${person.id}.jpg" else ""
            person.copy(photoPath = relativePhoto)
        }

        val peopleArray = JSONArray()
        portablePeople.forEach { p ->
            peopleArray.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name.trim())
                put("phone", p.phone)
                put("photoPath", p.photoPath.orEmpty())
            })
        }

        val entriesArray = JSONArray()
        state.entries.forEach { e ->
            entriesArray.put(JSONObject().apply {
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

        val ledgerJson = JSONObject().apply {
            put("version", 2)
            put("people", peopleArray)
            put("entries", entriesArray)
        }.toString(2)

        val manifestJson = JSONObject().apply {
            put("app", manifest.app)
            put("backupSchemaVersion", manifest.backupSchemaVersion)
            put("exportedAt", manifest.exportedAt)
            put("peopleCount", manifest.peopleCount)
            put("entriesCount", manifest.entriesCount)
            put("photosCount", manifest.photosCount)
        }.toString(2)

        val zipOut = ZipOutputStream(BufferedOutputStream(outputStream))
        try {
            // 1. Write manifest.json
            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifestJson.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 2. Write ledger.json
            zipOut.putNextEntry(ZipEntry("ledger.json"))
            zipOut.write(ledgerJson.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 3. Write photos
            for (person in peopleWithPhotos) {
                val photoFile = File(person.photoPath!!)
                if (photoFile.exists()) {
                    zipOut.putNextEntry(ZipEntry("images/${person.id}.jpg"))
                    photoFile.inputStream().use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }

            zipOut.finish()
            zipOut.flush()
        } finally {
            try { zipOut.close() } catch (_: Exception) {}
        }

        return manifest
    }

    /**
     * Inspects a backup stream (either ZIP or legacy JSON) without modifying application data.
     * Validates format, app identifier, schema version, and all financial rules.
     * Returns a BackupPreview describing the backup contents.
     */
    fun inspectBackup(
        inputStream: InputStream,
        stagingParentDir: File
    ): BackupPreview {
        val bufferedInput = BufferedInputStream(inputStream)
        bufferedInput.mark(4)
        val magic = ByteArray(4)
        val bytesRead = bufferedInput.read(magic)
        bufferedInput.reset()

        val isZip = bytesRead >= 4 &&
                magic[0] == 0x50.toByte() &&
                magic[1] == 0x4B.toByte() &&
                magic[2] == 0x03.toByte() &&
                magic[3] == 0x04.toByte()

        return if (isZip) {
            inspectZipBackup(bufferedInput, stagingParentDir)
        } else {
            inspectJsonBackup(bufferedInput)
        }
    }

    private fun inspectZipBackup(
        inputStream: InputStream,
        stagingParentDir: File
    ): BackupPreview {
        val stagingDir = File(stagingParentDir, "inspect_stage_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val canonicalStagingPath = stagingDir.canonicalPath

        var manifestText: String? = null
        var ledgerText: String? = null
        val extractedPhotos = mutableMapOf<String, File>()

        var totalBytesRead = 0L
        var entriesCount = 0

        val zipIn = ZipInputStream(inputStream)
        try {
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                entriesCount++
                if (entriesCount > MAX_ENTRIES_COUNT) {
                    throw InvalidBackupException("Backup archive exceeds maximum entry limit ($MAX_ENTRIES_COUNT)")
                }

                val entryName = entry.name.replace('\\', '/')
                // Reject Zip Slip / Path Traversal attempts
                if (entryName.startsWith("/") || entryName.contains(":") || entryName.split("/").contains("..")) {
                    throw SecurityException("Illegal ZIP entry path: ${entry.name}")
                }

                if (entryName == "manifest.json") {
                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var len: Int
                    while (zipIn.read(buffer).also { len = it } != -1) {
                        totalBytesRead += len
                        if (totalBytesRead > MAX_UNCOMPRESSED_BYTES) throw SecurityException("Decompressed size exceeds safe limit")
                        baos.write(buffer, 0, len)
                    }
                    manifestText = baos.toString(Charsets.UTF_8.name())
                } else if (entryName == "ledger.json") {
                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var len: Int
                    while (zipIn.read(buffer).also { len = it } != -1) {
                        totalBytesRead += len
                        if (totalBytesRead > MAX_UNCOMPRESSED_BYTES) throw SecurityException("Decompressed size exceeds safe limit")
                        baos.write(buffer, 0, len)
                    }
                    ledgerText = baos.toString(Charsets.UTF_8.name())
                } else if (entryName.startsWith("images/") && !entry.isDirectory) {
                    val filename = entryName.substringAfterLast('/')
                    val destFile = File(stagingDir, filename)
                    if (!destFile.canonicalPath.startsWith(canonicalStagingPath + File.separator)) {
                        throw SecurityException("Zip traversal detected: $entryName")
                    }

                    val fos = FileOutputStream(destFile)
                    val buffer = ByteArray(4096)
                    var len: Int
                    while (zipIn.read(buffer).also { len = it } != -1) {
                        totalBytesRead += len
                        if (totalBytesRead > MAX_UNCOMPRESSED_BYTES) throw SecurityException("Decompressed size exceeds safe limit")
                        fos.write(buffer, 0, len)
                    }
                    fos.flush()
                    fos.close()

                    val personId = filename.substringBeforeLast('.')
                    extractedPhotos[personId] = destFile
                }

                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        } finally {
            try { zipIn.close() } catch (_: Exception) {}
        }

        if (ledgerText.isNullOrBlank()) {
            stagingDir.deleteRecursively()
            throw InvalidBackupException("Backup archive is missing ledger.json")
        }

        // Validate manifest
        val manifestObj = if (!manifestText.isNullOrBlank()) {
            try { JSONObject(manifestText) } catch (e: Exception) {
                stagingDir.deleteRecursively()
                throw InvalidBackupException("Malformed manifest.json in backup", e)
            }
        } else null

        val app = manifestObj?.optString("app") ?: "Dueo"
        if (app != "Dueo") {
            stagingDir.deleteRecursively()
            throw InvalidBackupException("Unknown application identifier: '$app'. Only 'Dueo' backups are supported.")
        }

        val schemaVersion = manifestObj?.optInt("backupSchemaVersion", 2) ?: 2
        if (schemaVersion !in 1..2) {
            stagingDir.deleteRecursively()
            throw InvalidBackupException("Unsupported backup schema version: $schemaVersion")
        }

        // Parse and validate ledger
        val state = parseAndValidateLedgerJson(ledgerText, stagingDir)
        val balance = state.entries.balance()

        return BackupPreview(
            app = app,
            schemaVersion = schemaVersion,
            exportedAt = manifestObj?.optLong("exportedAt") ?: System.currentTimeMillis(),
            peopleCount = state.people.size,
            entriesCount = state.entries.size,
            photosCount = extractedPhotos.size,
            earliestDate = state.entries.minOfOrNull { it.date },
            latestDate = state.entries.maxOfOrNull { it.date },
            totalReceive = balance.receive,
            totalPay = balance.pay,
            isZipArchive = true,
            validatedState = state,
            extractedPhotos = extractedPhotos,
            stagingDir = stagingDir
        )
    }

    private fun inspectJsonBackup(inputStream: InputStream): BackupPreview {
        val rawJson = inputStream.bufferedReader().use { it.readText() }
        if (rawJson.trim().isEmpty()) {
            throw InvalidBackupException("Backup file is empty")
        }

        val root: JSONObject
        try {
            root = JSONObject(rawJson)
        } catch (e: Exception) {
            throw InvalidBackupException("Invalid JSON format in backup file", e)
        }

        val app = root.optString("app", "Dueo")
        if (app != "Dueo") {
            throw InvalidBackupException("Unknown application identifier: '$app'")
        }

        val schemaVersion = root.optInt("backupSchemaVersion", root.optInt("version", 1))
        if (schemaVersion !in 1..2) {
            throw InvalidBackupException("Unsupported backup schema version: $schemaVersion")
        }

        val state = parseAndValidateLedgerJson(rawJson, null)
        val balance = state.entries.balance()

        return BackupPreview(
            app = app,
            schemaVersion = schemaVersion,
            exportedAt = root.optLong("exportedAt", System.currentTimeMillis()),
            peopleCount = state.people.size,
            entriesCount = state.entries.size,
            photosCount = 0,
            earliestDate = state.entries.minOfOrNull { it.date },
            latestDate = state.entries.maxOfOrNull { it.date },
            totalReceive = balance.receive,
            totalPay = balance.pay,
            isZipArchive = false,
            validatedState = state,
            extractedPhotos = emptyMap(),
            stagingDir = null
        )
    }

    private fun parseAndValidateLedgerJson(jsonText: String, stagingDirToCleanOnError: File?): LedgerState {
        try {
            val root = JSONObject(jsonText)
            val peopleArray = root.getJSONArray("people")
            val entriesArray = root.getJSONArray("entries")

            val people = List(peopleArray.length()) { i ->
                val obj = peopleArray.getJSONObject(i)
                Person(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    phone = obj.optString("phone"),
                    photoPath = obj.optString("photoPath").takeIf { it.isNotBlank() }
                )
            }

            val entries = List(entriesArray.length()) { i ->
                val row = entriesArray.getJSONObject(i)
                val dueDate = if (row.has("dueDate") && !row.isNull("dueDate")) {
                    row.getLong("dueDate").takeIf { it > 0 }
                } else null

                Entry(
                    id = row.getString("id"),
                    person = row.getString("person"),
                    personId = row.getString("personId"),
                    amount = row.getLong("amount"),
                    kind = EntryKind.valueOf(row.getString("kind")),
                    note = row.optString("note", ""),
                    date = row.getLong("date"),
                    dueDate = dueDate
                )
            }

            val state = LedgerState(people = people, entries = entries)
            // Authoritative financial validation
            FinancialValidator.validateLedger(state)
            return state
        } catch (e: Exception) {
            stagingDirToCleanOnError?.deleteRecursively()
            throw InvalidBackupException("Ledger data validation failed: ${e.message}", e)
        }
    }

    /**
     * Creates a recoverable pre-restore safety snapshot of the current persistent ledger file.
     * Stored in filesDir/pre_restore_snapshots/snapshot-<timestamp>.json.
     */
    fun createPreRestoreSnapshot(filesDir: File): File? {
        val ledgerFile = File(filesDir, "ledger-v2.json")
        if (!ledgerFile.exists()) return null

        val snapshotsDir = File(filesDir, "pre_restore_snapshots").apply { mkdirs() }
        val snapshotFile = File(snapshotsDir, "snapshot-${System.currentTimeMillis()}.json")
        ledgerFile.copyTo(snapshotFile, overwrite = true)

        // Prune old snapshots, retaining the newest 10
        pruneSnapshots(snapshotsDir, keepCount = 10)
        return snapshotFile
    }

    private fun pruneSnapshots(snapshotsDir: File, keepCount: Int) {
        runCatching {
            val files = snapshotsDir.listFiles { file -> file.name.startsWith("snapshot-") } ?: return@runCatching
            if (files.size > keepCount) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - keepCount)
                    .forEach { it.delete() }
            }
        }
    }
}

