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

    @Test
    fun portableZipBackupExportsAndRestoresWithPhotosSuccessfully() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val store = LedgerStore(filesDir = filesDir, cacheDir = cacheDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()

        // 1. Create a person with a real photo file (valid JPEG magic bytes)
        val photosDir = File(filesDir, "people-photos").apply { mkdirs() }
        val testPhoto = File(photosDir, "test-person.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
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
        assertEquals(4, restoredPhotoFile.length())
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
        val initialPhoto = File(sourcePhotosDir, "initial.jpg").apply { writeText("original photo content") }
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
        val originalPhoto = File(photosDir, "orig.jpg").apply { writeText("unique original photo data") }
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
        assertEquals("unique original photo data", originalPhoto.readText())
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
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"peopleCount":0,"entriesCount":0,"photosCount":0}""".toByteArray())
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
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"peopleCount":1,"entriesCount":0,"photosCount":1}""".toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("ledger.json"))
        zos.write("""{"version":2,"people":[{"id":"p-1","name":"User 1","photoPath":"images/p-1.jpg"}],"entries":[]}""".toByteArray())
        zos.closeEntry()

        // Executable disguised as JPG (magic bytes not JPEG or PNG)
        zos.putNextEntry(ZipEntry("images/p-1.jpg"))
        zos.write("MZ\u0090\u0000 fake executable bytes".toByteArray())
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
    fun manifestCountMismatchIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_mismatch")
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)

        // Manifest claims 50 people
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write("""{"app":"Dueo","backupSchemaVersion":2,"peopleCount":50,"entriesCount":0,"photosCount":0}""".toByteArray())
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
        // Stream that produces more than 10 MB of data
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
