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

    const val MAX_JSON_FILE_BYTES = 10L * 1024L * 1024L // 10 MB maximum for JSON backup files
    const val MAX_TOTAL_UNCOMPRESSED_ZIP_BYTES = 25L * 1024L * 1024L // 25 MB max total uncompressed ZIP
    const val MAX_IMAGE_FILE_BYTES = 5L * 1024L * 1024L // 5 MB max per image
    const val MAX_ZIP_ENTRIES_COUNT = 500

    private val ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    private val IMAGE_ENTRY_REGEX = Regex("^images/[a-zA-Z0-9_-]{1,64}\\.(jpg|jpeg|png)$")

    /**
     * Reads an InputStream up to maxBytes. Throws InvalidBackupException if stream exceeds the limit.
     */
    fun readBoundedStream(inputStream: InputStream, maxBytes: Long): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalBytes = 0L
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            totalBytes += read
            if (totalBytes > maxBytes) {
                throw InvalidBackupException("Backup file exceeds maximum allowed size of ${maxBytes / (1024 * 1024)} MB")
            }
            baos.write(buffer, 0, read)
        }
        return baos.toByteArray()
    }

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

        // Map state with portable relative photo paths (images/<personId>.<ext>)
        val portablePeople = state.people.map { person ->
            val photoFile = person.photoPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            val hasPhoto = photoFile != null && photoFile.exists()
            val ext = if (hasPhoto) (photoFile.extension.takeIf { it.isNotBlank() } ?: "jpg") else "jpg"
            val relativePhoto = if (hasPhoto) "images/${person.id}.$ext" else ""
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
                if (!e.clientOperationId.isNullOrBlank()) {
                    put("clientOperationId", e.clientOperationId)
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
                    val ext = photoFile.extension.takeIf { it.isNotBlank() } ?: "jpg"
                    zipOut.putNextEntry(ZipEntry("images/${person.id}.$ext"))
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
     * Validates format, app identifier, schema version, path safety, and all financial rules.
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
        val seenZipEntries = mutableSetOf<String>()
        val seenPhotoPersonIds = mutableSetOf<String>()

        var totalDecompressedBytes = 0L
        var entriesCount = 0
        var previewCreated = false

        try {
            val zipIn = ZipInputStream(inputStream)
            try {
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    entriesCount++
                    if (entriesCount > MAX_ZIP_ENTRIES_COUNT) {
                        throw InvalidBackupException("Backup archive exceeds maximum entry limit ($MAX_ZIP_ENTRIES_COUNT)")
                    }

                    val entryName = entry.name.replace('\\', '/')

                    // Strict duplicate entry rejection
                    if (!seenZipEntries.add(entryName)) {
                        throw SecurityException("Duplicate entry in backup archive: $entryName")
                    }

                    // Strict path traversal / zip slip check
                    if (entryName.startsWith("/") || entryName.contains(":") || entryName.split("/").contains("..") || entryName.contains("\u0000")) {
                        throw SecurityException("Illegal ZIP entry path: ${entry.name}")
                    }

                    // Strict whitelist of permitted file structures in container
                    if (entryName != "manifest.json" && entryName != "ledger.json" && !entryName.matches(IMAGE_ENTRY_REGEX)) {
                        throw SecurityException("Unauthorized or malformed entry in backup archive: $entryName")
                    }

                    if (entryName == "manifest.json") {
                        val bytes = readBoundedEntry(zipIn, 1024L * 1024L) // 1 MB limit for manifest
                        totalDecompressedBytes += bytes.size
                        if (totalDecompressedBytes > MAX_TOTAL_UNCOMPRESSED_ZIP_BYTES) throw SecurityException("Decompressed size exceeds safe limit")
                        manifestText = String(bytes, Charsets.UTF_8)
                    } else if (entryName == "ledger.json") {
                        val bytes = readBoundedEntry(zipIn, MAX_JSON_FILE_BYTES) // 10 MB limit for ledger JSON
                        totalDecompressedBytes += bytes.size
                        if (totalDecompressedBytes > MAX_TOTAL_UNCOMPRESSED_ZIP_BYTES) throw SecurityException("Decompressed size exceeds safe limit")
                        ledgerText = String(bytes, Charsets.UTF_8)
                    } else if (entryName.startsWith("images/") && !entry.isDirectory) {
                        val filename = entryName.substringAfterLast('/')
                        val personId = filename.substringBeforeLast('.')

                        // Reject conflicting image entries for the same person ID
                        if (!seenPhotoPersonIds.add(personId)) {
                            throw InvalidBackupException("Conflicting profile images for person: '$personId'")
                        }

                        val destFile = File(stagingDir, filename)
                        if (!destFile.canonicalPath.startsWith(canonicalStagingPath + File.separator)) {
                            throw SecurityException("Zip traversal detected: $entryName")
                        }

                        val bytes = readBoundedEntry(zipIn, MAX_IMAGE_FILE_BYTES)
                        totalDecompressedBytes += bytes.size
                        if (totalDecompressedBytes > MAX_TOTAL_UNCOMPRESSED_ZIP_BYTES) throw SecurityException("Decompressed size exceeds safe limit")

                        // Validate image decoding, dimensions, and file size
                        validateImageBytes(bytes, entryName)

                        destFile.outputStream().use { it.write(bytes) }
                        extractedPhotos[personId] = destFile
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            } finally {
                try { zipIn.close() } catch (_: Exception) {}
            }

            if (manifestText.isNullOrBlank()) {
                throw InvalidBackupException("Backup archive is missing required manifest.json")
            }

            if (ledgerText.isNullOrBlank()) {
                throw InvalidBackupException("Backup archive is missing required ledger.json")
            }

            // Validate mandatory manifest fields and types
            val manifestObj = try {
                JSONObject(manifestText)
            } catch (e: Exception) {
                throw InvalidBackupException("Malformed manifest.json in backup archive", e)
            }

            if (!manifestObj.has("app") || manifestObj.isNull("app")) {
                throw InvalidBackupException("Missing mandatory manifest field: 'app'")
            }
            val app = manifestObj.getString("app")
            if (app != "Dueo") {
                throw InvalidBackupException("Unknown application identifier: '$app'. Only 'Dueo' backups are supported.")
            }

            if (!manifestObj.has("backupSchemaVersion") || manifestObj.isNull("backupSchemaVersion")) {
                throw InvalidBackupException("Missing mandatory manifest field: 'backupSchemaVersion'")
            }
            val schemaVersion = manifestObj.getInt("backupSchemaVersion")
            if (schemaVersion !in 1..2) {
                throw InvalidBackupException("Unsupported backup schema version: $schemaVersion")
            }

            if (!manifestObj.has("exportedAt") || manifestObj.isNull("exportedAt")) {
                throw InvalidBackupException("Missing mandatory manifest field: 'exportedAt'")
            }
            val exportedAt = manifestObj.getLong("exportedAt")
            if (exportedAt <= 0L) {
                throw InvalidBackupException("Invalid exportedAt timestamp in manifest: $exportedAt")
            }

            if (!manifestObj.has("peopleCount") || manifestObj.isNull("peopleCount")) {
                throw InvalidBackupException("Missing mandatory manifest field: 'peopleCount'")
            }
            val manifestPeople = manifestObj.getInt("peopleCount")
            if (manifestPeople < 0) {
                throw InvalidBackupException("Invalid peopleCount in manifest: $manifestPeople")
            }

            if (!manifestObj.has("entriesCount") || manifestObj.isNull("entriesCount")) {
                throw InvalidBackupException("Missing mandatory manifest field: 'entriesCount'")
            }
            val manifestEntries = manifestObj.getInt("entriesCount")
            if (manifestEntries < 0) {
                throw InvalidBackupException("Invalid entriesCount in manifest: $manifestEntries")
            }

            if (!manifestObj.has("photosCount") || manifestObj.isNull("photosCount")) {
                throw InvalidBackupException("Missing mandatory manifest field: 'photosCount'")
            }
            val manifestPhotos = manifestObj.getInt("photosCount")
            if (manifestPhotos < 0) {
                throw InvalidBackupException("Invalid photosCount in manifest: $manifestPhotos")
            }

            // Parse and validate ledger
            val state = parseAndValidateLedgerJson(ledgerText, stagingDir)

            // Validate that manifest counts strictly match parsed ledger contents
            if (manifestPeople != state.people.size) {
                throw InvalidBackupException("Manifest people count ($manifestPeople) does not match ledger contents (${state.people.size})")
            }
            if (manifestEntries != state.entries.size) {
                throw InvalidBackupException("Manifest entries count ($manifestEntries) does not match ledger contents (${state.entries.size})")
            }
            if (manifestPhotos != extractedPhotos.size) {
                throw InvalidBackupException("Manifest photos count ($manifestPhotos) does not match extracted photos count (${extractedPhotos.size})")
            }

            // Validate that every person with a referenced photo has their image in the archive
            for (person in state.people) {
                if (!person.photoPath.isNullOrBlank()) {
                    val photoFile = extractedPhotos[person.id]
                    if (photoFile == null || !photoFile.exists()) {
                        throw InvalidBackupException("Referenced profile photo for person '${person.name}' (${person.id}) is missing from backup")
                    }
                }
            }

            // Validate that all extracted photos belong to known people who actually reference a photo
            val personIdsWithPhoto = state.people.filter { !it.photoPath.isNullOrBlank() }.map { it.id }.toSet()
            for (personId in extractedPhotos.keys) {
                if (!personIdsWithPhoto.contains(personId)) {
                    throw SecurityException("Backup contains unreferenced profile image for person ID: '$personId'")
                }
            }

            val balance = state.entries.balance()

            val preview = BackupPreview(
                app = app,
                schemaVersion = schemaVersion,
                exportedAt = exportedAt,
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
            previewCreated = true
            return preview
        } finally {
            if (!previewCreated) {
                stagingDir.deleteRecursively()
            }
        }
    }

    private fun validateImageBytes(bytes: ByteArray, entryName: String) {
        if (bytes.size < 24) {
            throw SecurityException("Malformed image in backup '$entryName': file too small (${bytes.size} bytes)")
        }
        if (bytes.size > MAX_IMAGE_FILE_BYTES) {
            throw SecurityException("Image file '$entryName' exceeds maximum allowed size of 5 MB")
        }

        val isPng = bytes.size >= 24 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()

        val isJpeg = bytes.size >= 4 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

        if (!isPng && !isJpeg) {
            throw SecurityException("Unsafe or invalid image content in backup '$entryName': must be a valid JPEG or PNG file")
        }

        var width = 0
        var height = 0

        if (isPng) {
            // PNG IHDR chunk is located immediately after the 8-byte PNG header
            // Bytes 8-11: chunk length (must be 13), Bytes 12-15: "IHDR"
            if (bytes[12] != 0x49.toByte() || bytes[13] != 0x48.toByte() || bytes[14] != 0x44.toByte() || bytes[15] != 0x52.toByte()) {
                throw SecurityException("Malformed PNG in backup '$entryName': missing IHDR chunk")
            }
            width = ((bytes[16].toInt() and 0xFF) shl 24) or ((bytes[17].toInt() and 0xFF) shl 16) or ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)
            height = ((bytes[20].toInt() and 0xFF) shl 24) or ((bytes[21].toInt() and 0xFF) shl 16) or ((bytes[22].toInt() and 0xFF) shl 8) or (bytes[23].toInt() and 0xFF)
            val bitDepth = bytes[24].toInt() and 0xFF
            val colorType = bytes[25].toInt() and 0xFF
            if (bitDepth !in listOf(1, 2, 4, 8, 16) || colorType !in listOf(0, 2, 3, 4, 6)) {
                throw SecurityException("Malformed PNG in backup '$entryName': invalid bit depth ($bitDepth) or color type ($colorType)")
            }
        } else {
            // JPEG segment scanning for SOF0 (0xC0), SOF1 (0xC1), SOF2 (0xC2), SOF3 (0xC3)
            var offset = 2
            var foundSof = false
            while (offset < bytes.size - 8) {
                if ((bytes[offset].toInt() and 0xFF) != 0xFF) {
                    offset++
                    continue
                }
                val marker = bytes[offset + 1].toInt() and 0xFF
                if (marker == 0xD9 || marker == 0xDA) {
                    // End of image or Start of scan reached
                    break
                }
                if (marker in 0xC0..0xC3) {
                    height = ((bytes[offset + 5].toInt() and 0xFF) shl 8) or (bytes[offset + 6].toInt() and 0xFF)
                    width = ((bytes[offset + 7].toInt() and 0xFF) shl 8) or (bytes[offset + 8].toInt() and 0xFF)
                    foundSof = true
                    break
                }
                val segLen = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
                if (segLen < 2) break
                offset += 2 + segLen
            }
            if (!foundSof || width <= 0 || height <= 0) {
                throw SecurityException("Malformed JPEG in backup '$entryName': missing or invalid SOF frame dimensions")
            }
        }

        if (width <= 0 || height <= 0) {
            throw SecurityException("Image '$entryName' has invalid dimensions: ${width}x${height}")
        }
        if (width > 8192 || height > 8192) {
            throw SecurityException("Image '$entryName' dimensions exceed maximum allowed size (8192x8192): ${width}x${height}")
        }

        // Platform decoding validation: attempt decoding with Android BitmapFactory or Java ImageIO
        var decodeSuccess = false
        var decodeAttempted = false

        // Try Android BitmapFactory
        try {
            val bfClass = Class.forName("android.graphics.BitmapFactory")
            val optsClass = Class.forName("android.graphics.BitmapFactory\$Options")
            val opts = optsClass.getConstructor().newInstance()
            optsClass.getField("inJustDecodeBounds").setBoolean(opts, true)
            val decodeMethod = bfClass.getMethod(
                "decodeByteArray",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                optsClass
            )
            decodeMethod.invoke(null, bytes, 0, bytes.size, opts)
            val outWidth = optsClass.getField("outWidth").getInt(opts)
            val outHeight = optsClass.getField("outHeight").getInt(opts)
            if (outWidth > 0 && outHeight > 0) {
                decodeSuccess = true
            }
            decodeAttempted = true
        } catch (_: Throwable) {}

        // Try Java ImageIO (desktop JVM)
        if (!decodeSuccess) {
            try {
                val imageIoClass = Class.forName("javax.imageio.ImageIO")
                val readMethod = imageIoClass.getMethod("read", InputStream::class.java)
                val bufferedImage = readMethod.invoke(null, ByteArrayInputStream(bytes))
                if (bufferedImage != null) {
                    val getWidthMethod = bufferedImage.javaClass.getMethod("getWidth")
                    val getHeightMethod = bufferedImage.javaClass.getMethod("getHeight")
                    val w = getWidthMethod.invoke(bufferedImage) as Int
                    val h = getHeightMethod.invoke(bufferedImage) as Int
                    if (w > 0 && h > 0) {
                        decodeSuccess = true
                    }
                }
                decodeAttempted = true
            } catch (_: Throwable) {}
        }

        if (decodeAttempted && !decodeSuccess) {
            throw SecurityException("Failed to decode image '$entryName': corrupted or unsupported image data")
        }
    }

    private fun readBoundedEntry(zipIn: ZipInputStream, maxEntryBytes: Long): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0L
        var len: Int
        while (zipIn.read(buffer).also { len = it } != -1) {
            total += len
            if (total > maxEntryBytes) {
                throw SecurityException("ZIP entry exceeds maximum safe size of ${maxEntryBytes / (1024 * 1024)} MB")
            }
            baos.write(buffer, 0, len)
        }
        return baos.toByteArray()
    }

    private fun inspectJsonBackup(inputStream: InputStream): BackupPreview {
        val rawBytes = readBoundedStream(inputStream, MAX_JSON_FILE_BYTES)
        val rawJson = String(rawBytes, Charsets.UTF_8)
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
                val id = obj.getString("id")
                if (!id.matches(ID_REGEX)) {
                    throw InvalidBackupException("Person ID '$id' contains invalid characters. Must be alphanumeric or hyphens/underscores.")
                }
                Person(
                    id = id,
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

                val clientOpId = if (row.has("clientOperationId") && !row.isNull("clientOperationId")) {
                    row.getString("clientOperationId").takeIf { it.isNotBlank() }
                } else null

                Entry(
                    id = row.getString("id"),
                    person = row.getString("person"),
                    personId = row.getString("personId"),
                    amount = row.getLong("amount"),
                    kind = EntryKind.valueOf(row.getString("kind")),
                    note = row.optString("note", ""),
                    date = row.getLong("date"),
                    dueDate = dueDate,
                    clientOperationId = clientOpId
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
     * Creates a recoverable pre-restore safety snapshot of the current persistent ledger file
     * and all existing profile photos.
     * Returns the snapshot folder File.
     */
    fun createPreRestoreSnapshot(filesDir: File): File? {
        val ledgerFile = File(filesDir, "ledger-v2.json")
        if (!ledgerFile.exists() || ledgerFile.length() == 0L) return null

        val snapshotsDir = File(filesDir, "pre_restore_snapshots").apply { mkdirs() }
        val snapshotFolder = File(snapshotsDir, "snapshot_${System.currentTimeMillis()}").apply { mkdirs() }

        // 1. Snapshot ledger-v2.json
        val ledgerDest = File(snapshotFolder, "ledger-v2.json")
        ledgerFile.copyTo(ledgerDest, overwrite = true)

        // 2. Snapshot people-photos directory
        val photosDir = File(filesDir, "people-photos")
        if (photosDir.exists() && photosDir.isDirectory) {
            val photosDest = File(snapshotFolder, "people-photos").apply { mkdirs() }
            photosDir.listFiles()?.forEach { photo ->
                if (photo.isFile) {
                    photo.copyTo(File(photosDest, photo.name), overwrite = true)
                }
            }
        }

        // Prune old snapshots, retaining the newest 10
        pruneSnapshots(snapshotsDir, keepCount = 10)
        return snapshotFolder
    }

    /**
     * Reverts all financial records and profile photos to the safety snapshot if restoration fails.
     */
    fun rollbackPreRestoreSnapshot(filesDir: File, snapshotFolder: File) {
        runCatching {
            // 1. Revert ledger-v2.json
            val snapshotLedger = File(snapshotFolder, "ledger-v2.json")
            val targetLedger = File(filesDir, "ledger-v2.json")
            val targetBak = File(filesDir, "ledger-v2.json.bak")
            targetBak.delete()
            if (snapshotLedger.exists()) {
                snapshotLedger.copyTo(targetLedger, overwrite = true)
            }

            // 2. Revert people-photos
            val snapshotPhotos = File(snapshotFolder, "people-photos")
            if (snapshotPhotos.exists() && snapshotPhotos.isDirectory) {
                val targetPhotos = File(filesDir, "people-photos").apply { mkdirs() }
                snapshotPhotos.listFiles()?.forEach { photo ->
                    photo.copyTo(File(targetPhotos, photo.name), overwrite = true)
                }
            }
        }
    }

    private fun pruneSnapshots(snapshotsDir: File, keepCount: Int) {
        runCatching {
            val folders = snapshotsDir.listFiles { file -> file.isDirectory && file.name.startsWith("snapshot_") } ?: return@runCatching
            if (folders.size > keepCount) {
                folders.sortedBy { it.lastModified() }
                    .take(folders.size - keepCount)
                    .forEach { it.deleteRecursively() }
            }
        }
    }
}
