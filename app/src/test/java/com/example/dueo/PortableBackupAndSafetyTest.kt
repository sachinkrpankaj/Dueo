package com.example.dueo

import com.example.dueo.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortableBackupAndSafetyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createValidJpgBytes(width: Int = 10, height: Int = 10): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val baos = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "jpg", baos)
        return baos.toByteArray()
    }

    private fun createValidPngBytes(width: Int = 10, height: Int = 10): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val baos = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", baos)
        return baos.toByteArray()
    }

    @Test
    fun portableZipBackupExportsAndRestoresWithPhotosSuccessfully() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val store = LedgerStore(filesDir = filesDir, cacheDir = cacheDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()

        // 1. Create a person with a real photo file (valid decodable JPEG)
        val photosDir = File(filesDir, "people-photos").apply { mkdirs() }
        val testPhoto = File(photosDir, "test-person.jpg").apply {
            writeBytes(createValidJpgBytes(16, 16))
        }
        val person = repository.addPerson("Kavita", "+919876543210", testPhoto.absolutePath).getOrThrow()

        // 2. Add transactions
        repository.addTransaction(
            personId = person.id,
            amount = 120000L,
            kind = EntryKind.LENT,
            note = "Rent advance"
        ).getOrThrow()

        repository.addTransaction(
            personId = person.id,
            amount = 40000L,
            kind = EntryKind.RECEIVED,
            note = "Part refund"
        ).getOrThrow()

        // 3. Export to ZIP
        val zipOutput = ByteArrayOutputStream()
        val exportManifestResult = repository.exportBackupZip(zipOutput, filesDir)
        assertTrue(exportManifestResult.isSuccess)
        val manifest = exportManifestResult.getOrThrow()
        assertEquals("Dueo", manifest.app)
        assertEquals(2, manifest.backupSchemaVersion)
        assertEquals(1, manifest.peopleCount)
        assertEquals(2, manifest.entriesCount)
        assertEquals(1, manifest.photosCount)

        val zipBytes = zipOutput.toByteArray()
        assertTrue(zipBytes.isNotEmpty())

        // 4. Inspect / preview backup on a fresh installation directory
        val restoreFilesDir = tempFolder.newFolder("restore_files")
        val restoreCacheDir = tempFolder.newFolder("restore_cache")
        val restoreStore = LedgerStore(filesDir = restoreFilesDir, cacheDir = restoreCacheDir)
        val restoreRepo = LedgerRepository(store = restoreStore)
        restoreRepo.initialize()

        val preview = restoreRepo.previewBackup(ByteArrayInputStream(zipBytes), restoreCacheDir).getOrThrow()
        assertEquals("Dueo", preview.app)
        assertEquals(2, preview.schemaVersion)
        assertEquals(1, preview.peopleCount)
        assertEquals(2, preview.entriesCount)
        assertEquals(1, preview.photosCount)
        assertTrue(preview.isZipArchive)
        assertEquals(80000L, preview.totalReceive)
        assertEquals(0L, preview.totalPay)

        // 5. Execute restore with confirmation
        val restoreStateResult = restoreRepo.executeRestore(preview, restoreFilesDir)
        assertTrue(restoreStateResult.isSuccess)
        val restored = restoreStateResult.getOrThrow()

        assertEquals(1, restored.people.size)
        assertEquals("Kavita", restored.people[0].name)
        assertNotNull(restored.people[0].photoPath)
        val restoredPhotoFile = File(restored.people[0].photoPath!!)
        assertTrue("Restored photo file must exist on disk", restoredPhotoFile.exists())
        assertTrue("Restored photo must be in permanent photos folder", restoredPhotoFile.absolutePath.contains("people-photos"))
        assertFalse("Restored photo must not refer to temp staging directory", restoredPhotoFile.absolutePath.contains("inspect_stage"))
        assertEquals(2, restored.entries.size)
        assertEquals(80000L, restored.entries.balance().receive)
    }

    @Test
    fun preRestoreSafetySnapshotPreservesBothLedgerAndProfilePhotos() = runBlocking {
        val filesDir = tempFolder.newFolder("files_snapshot")
        val cacheDir = tempFolder.newFolder("cache_snapshot")
        val store = LedgerStore(filesDir = filesDir, cacheDir = cacheDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()
        val sourcePhotosDir = tempFolder.newFolder("source_photos")
        val initialPhoto = File(sourcePhotosDir, "initial.jpg").apply { writeBytes(createValidJpgBytes(16, 16)) }
        val originalPerson = repository.addPerson("Pre-existing User", "111", initialPhoto.absolutePath).getOrThrow()
        repository.addTransaction(originalPerson.id, 50000L, EntryKind.LENT, "Initial loan").getOrThrow()

        // Verify ledger and photo exist
        assertTrue(store.ledgerFile.exists())
        assertNotNull(originalPerson.photoPath)
        val savedPhoto = File(originalPerson.photoPath!!)
        assertTrue(savedPhoto.exists())

        // Create safety snapshot
        val snapshotFolder = BackupManager.createPreRestoreSnapshot(filesDir)
        assertNotNull(snapshotFolder)
        assertTrue(snapshotFolder!!.exists())

        val snapshotLedger = File(snapshotFolder, "ledger-v2.json")
        assertTrue("Snapshot must contain ledger-v2.json", snapshotLedger.exists())
        assertTrue(snapshotLedger.readText().contains("Pre-existing User"))

        val snapshotPhotos = File(snapshotFolder, "people-photos")
        assertTrue("Snapshot must contain people-photos folder", snapshotPhotos.exists())
        assertEquals(1, snapshotPhotos.listFiles()?.size)
    }

    @Test
    fun failedRestoreRollsBackAndPreservesOriginalLedgerAndPhotos() = runBlocking {
        val filesDir = tempFolder.newFolder("files_rollback")
        val cacheDir = tempFolder.newFolder("cache_rollback")
        val store = LedgerStore(filesDir = filesDir, cacheDir = cacheDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()
        val photosDir = File(filesDir, "people-photos").apply { mkdirs() }
        val originalPhoto = File(photosDir, "orig.jpg").apply { writeBytes(createValidJpgBytes(16, 16)) }
        val originalPerson = repository.addPerson("Untouchable User", "999", originalPhoto.absolutePath).getOrThrow()
        repository.addTransaction(originalPerson.id, 75000L, EntryKind.LENT, "Important record").getOrThrow()

        // Corrupted preview with invalid person reference in entries causing restore to fail during validation
        val badState = LedgerState(
            people = listOf(Person(id = "p-corrupt", name = "Corrupt Person")),
            entries = listOf(Entry(id = "e-bad", person = "Corrupt Person", personId = "non-existent-fk", amount = 1000L, kind = EntryKind.LENT))
        )
        val badPreview = BackupPreview(
            app = "Dueo",
            schemaVersion = 2,
            exportedAt = System.currentTimeMillis(),
            peopleCount = 1,
            entriesCount = 1,
            photosCount = 0,
            earliestDate = 1000L,
            latestDate = 1000L,
            totalReceive = 1000L,
            totalPay = 0L,
            isZipArchive = false,
            validatedState = badState
        )

        val restoreResult = repository.executeRestore(badPreview, filesDir)
        assertTrue("Restoration must fail due to foreign key integrity", restoreResult.isFailure)

        // Verify original ledger is completely intact
        val currentLedger = repository.getCurrentState()!!
        assertEquals(1, currentLedger.people.size)
        assertEquals("Untouchable User", currentLedger.people[0].name)
        assertEquals(1, currentLedger.entries.size)
        assertEquals(75000L, currentLedger.entries[0].amount)

        // Verify original photo was preserved
        assertTrue("Original photo must exist after failed restore", originalPhoto.exists())
    }

    @Test
    fun zipSlipDirectoryTraversalAttemptIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_zipslip")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        // Malicious entry attempting path traversal outside extraction directory
        zos.putNextEntry(ZipEntry("../../malicious.txt"))
        zos.write("evil payload".toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        zos.finish()
        zos.close()

        val badZipBytes = baos.toByteArray()
        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(badZipBytes), cacheDir)
        }
        assertTrue("ZipSlip attack must fail", result.isFailure)
        assertTrue("Exception must be SecurityException", result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun maliciousEntryInZipIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_malicious_entry")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"exportedAt":1000,"peopleCount":0,"entriesCount":0,"photosCount":0}""".toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[],"entries":[]}""".toByteArray())
        zos.closeEntry()

        // Unauthorized script entry
        zos.putNextEntry(ZipEntry("exploit.sh"))
        zos.write("echo bad".toByteArray())
        zos.closeEntry()

        zos.finish()
        zos.close()

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir)
        }
        assertTrue("ZIP with unauthorized file must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun imageWithInvalidMagicBytesIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_bad_magic")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"exportedAt":1000,"peopleCount":1,"entriesCount":0,"photosCount":1}""".toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[{"id":"p-1","name":"User 1","photoPath":"images/p-1.jpg"}],"entries":[]}""".toByteArray())
        zos.closeEntry()

        // Executable disguised as JPG (magic bytes not JPEG or PNG)
        zos.putNextEntry(ZipEntry("images/p-1.jpg"))
        zos.write("MZ\u0090\u0000 fake executable bytes that are not jpeg or png".toByteArray())
        zos.closeEntry()

        zos.finish()
        zos.close()

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir)
        }
        assertTrue("Disguised non-image file must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun duplicateZipEntryIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_duplicate_entry")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"exportedAt":1000,"peopleCount":0,"entriesCount":0,"photosCount":0}""".toByteArray())
        zos.closeEntry()

        // Write second entry with identical-length placeholder name
        zos.putNextEntry(ZipEntry("xanifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"exportedAt":1000,"peopleCount":0,"entriesCount":0,"photosCount":0}""".toByteArray())
        zos.closeEntry()

        zos.finish()
        zos.close()

        // Mutate "xanifest.json" to "manifest.json" in the byte array so archive has real duplicate entry names
        val zipBytes = baos.toByteArray()
        val target = "xanifest.json".toByteArray(Charsets.US_ASCII)
        for (i in 0 until zipBytes.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (zipBytes[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                zipBytes[i] = 'm'.code.toByte()
            }
        }

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(zipBytes), cacheDir)
        }
        assertTrue("Duplicate ZIP entry must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun conflictingImagesForSamePersonIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_conflict_images")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        val jpgBytes = createValidJpgBytes(10, 10)
        val pngBytes = createValidPngBytes(10, 10)

        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"exportedAt":1000,"peopleCount":1,"entriesCount":0,"photosCount":1}""".toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[{"id":"p-1","name":"User 1","photoPath":"images/p-1.jpg"}],"entries":[]}""".toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("images/p-1.jpg"))
        zos.write(jpgBytes)
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("images/p-1.png"))
        zos.write(pngBytes)
        zos.closeEntry()

        zos.finish()
        zos.close()

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir)
        }
        assertTrue("Conflicting images for the same person must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidBackupException)
    }

    @Test
    fun missingReferencedPhotoIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_missing_photo")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"exportedAt":1000,"peopleCount":1,"entriesCount":0,"photosCount":1}""".toByteArray())
        zos.closeEntry()

        // Person claims to have photo at images/p-missing.jpg, but it is NOT in the zip!
        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[{"id":"p-missing","name":"User 1","photoPath":"images/p-missing.jpg"}],"entries":[]}""".toByteArray())
        zos.closeEntry()

        zos.finish()
        zos.close()

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir)
        }
        assertTrue("Missing referenced photo must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidBackupException)
    }

    @Test
    fun unreferencedPhotoInZipIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_unref_photo")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        val jpgBytes = createValidJpgBytes(10, 10)

        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"exportedAt":1000,"peopleCount":0,"entriesCount":0,"photosCount":1}""".toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[],"entries":[]}""".toByteArray())
        zos.closeEntry()

        // Photo for a person who doesn't exist in the ledger
        zos.putNextEntry(ZipEntry("images/p-ghost.jpg"))
        zos.write(jpgBytes)
        zos.closeEntry()

        zos.finish()
        zos.close()

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir)
        }
        assertTrue("Unreferenced photo in zip must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun missingMandatoryManifestFieldsIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_missing_fields")

        // Missing "app"
        var baos = ByteArrayOutputStream()
        var zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"backupSchemaVersion":2,"exportedAt":1000,"peopleCount":0,"entriesCount":0,"photosCount":0}""".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[],"entries":[]}""".toByteArray())
        zos.closeEntry()
        zos.finish()
        zos.close()

        var result = runCatching { BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir) }
        assertTrue("Missing app field must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidBackupException)

        // Missing "backupSchemaVersion"
        baos = ByteArrayOutputStream()
        zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","exportedAt":1000,"peopleCount":0,"entriesCount":0,"photosCount":0}""".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[],"entries":[]}""".toByteArray())
        zos.closeEntry()
        zos.finish()
        zos.close()

        result = runCatching { BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir) }
        assertTrue("Missing schema version must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidBackupException)

        // Missing "exportedAt"
        baos = ByteArrayOutputStream()
        zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"peopleCount":0,"entriesCount":0,"photosCount":0}""".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[],"entries":[]}""".toByteArray())
        zos.closeEntry()
        zos.finish()
        zos.close()

        result = runCatching { BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir) }
        assertTrue("Missing exportedAt must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidBackupException)
    }

    @Test
    fun temporaryFilesCleanedUpOnInspectionFailure() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_cleanup_test")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        // Manifest is malformed
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("not-json-content".toByteArray())
        zos.closeEntry()
        zos.finish()
        zos.close()

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir)
        }
        assertTrue(result.isFailure)

        // Verify that no staging directory was left behind in cacheDir
        val remainingFiles = cacheDir.listFiles()?.filter { it.name.startsWith("inspect_stage_") }
        assertTrue("Temporary staging folders must be deleted on failure", remainingFiles.isNullOrEmpty())
    }

    @Test
    fun crashSafePhotoRestorationDoesNotOverwriteExistingPhotosAndUsesPermanentPaths() = runBlocking {
        val filesDir = tempFolder.newFolder("files_safe_restore")
        val cacheDir = tempFolder.newFolder("cache_safe_restore")
        val store = LedgerStore(filesDir = filesDir, cacheDir = cacheDir)
        val repository = LedgerRepository(store = store)
        repository.initialize()

        // 1. Existing person with unique photo
        val existingPhoto = File(filesDir, "people-photos").apply { mkdirs() }
            .let { File(it, "original_existing_photo.jpg") }
            .apply { writeBytes(createValidJpgBytes(20, 20)) }
        val existingPerson = repository.addPerson("Original User", "111", existingPhoto.absolutePath).getOrThrow()
        repository.addTransaction(existingPerson.id, 50000L, EntryKind.LENT, "Old loan").getOrThrow()

        // 2. Prepare incoming backup with a person named Restored User
        val incomingPhoto = File(tempFolder.newFolder("incoming_source"), "restored.jpg").apply {
            writeBytes(createValidJpgBytes(30, 30))
        }
        val incomingState = LedgerState(
            people = listOf(Person(id = "p-new", name = "Restored User", photoPath = "images/p-new.jpg")),
            entries = listOf(Entry(id = "e-new", person = "Restored User", personId = "p-new", amount = 80000L, kind = EntryKind.LENT))
        )
        val stagingDir = File(cacheDir, "staging_test").apply { mkdirs() }
        val stagedPhoto = File(stagingDir, "p-new.jpg")
        incomingPhoto.copyTo(stagedPhoto)

        val preview = BackupPreview(
            app = "Dueo",
            schemaVersion = 2,
            exportedAt = System.currentTimeMillis(),
            peopleCount = 1,
            entriesCount = 1,
            photosCount = 1,
            earliestDate = 1000L,
            latestDate = 1000L,
            totalReceive = 80000L,
            totalPay = 0L,
            isZipArchive = true,
            validatedState = incomingState,
            extractedPhotos = mapOf("p-new" to stagedPhoto),
            stagingDir = stagingDir
        )

        // 3. Restore
        val restoreResult = repository.executeRestore(preview, filesDir)
        assertTrue(restoreResult.isSuccess)
        val restoredState = restoreResult.getOrThrow()

        // 4. Verify permanent path is used, NOT staging path
        val restoredPerson = restoredState.people[0]
        assertNotNull(restoredPerson.photoPath)
        val restoredFile = File(restoredPerson.photoPath!!)
        assertTrue(restoredFile.exists())
        assertTrue("Restored photo must be in permanent people-photos dir", restoredFile.absolutePath.contains("people-photos"))
        assertFalse("Restored photo path must not reference staging folder", restoredFile.absolutePath.contains("staging_test"))

        // 5. Verify the original photo was never overwritten
        assertTrue("Original photo file must remain intact", existingPhoto.exists())
        assertEquals("Original photo content size must match", createValidJpgBytes(20, 20).size.toLong(), existingPhoto.length())
    }

    @Test
    fun manifestCountMismatchIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_mismatch")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        // Manifest claims 50 people
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"exportedAt":1000,"peopleCount":50,"entriesCount":0,"photosCount":0}""".toByteArray())
        zos.closeEntry()

        // Ledger only has 1 person
        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[{"id":"p-1","name":"User 1","photoPath":""}],"entries":[]}""".toByteArray())
        zos.closeEntry()

        zos.finish()
        zos.close()

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(baos.toByteArray()), cacheDir)
        }
        assertTrue("Count mismatch must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidBackupException)
    }

    @Test
    fun oversizedJsonBackupIsRejected() {
        val hugeStream = object : InputStream() {
            private var bytesProduced = 0L
            override fun read(): Int {
                bytesProduced++
                return '{'.code
            }
        }

        val result = runCatching {
            BackupManager.readBoundedStream(hugeStream, BackupManager.MAX_JSON_FILE_BYTES)
        }
        assertTrue("Oversized stream must be halted and rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidBackupException)
    }
}
