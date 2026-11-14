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

        // 1. Create a person with a real photo file
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
    fun preRestoreSafetySnapshotIsCreatedBeforeOverwriting() = runBlocking {
        val filesDir = tempFolder.newFolder("files_snapshot")
        val cacheDir = tempFolder.newFolder("cache_snapshot")
        val store = LedgerStore(filesDir = filesDir, cacheDir = cacheDir)
        val repository = LedgerRepository(store = store)

        repository.initialize()
        val originalPerson = repository.addPerson("Pre-existing User", "111", null).getOrThrow()
        repository.addTransaction(originalPerson.id, 50000L, EntryKind.LENT, "Initial loan").getOrThrow()

        // Verify ledger exists
        assertTrue(store.ledgerFile.exists())

        // Prepare a new backup to restore
        val newBackupJson = """{
            "app": "Dueo",
            "backupSchemaVersion": 2,
            "exportedAt": 1700000000000,
            "people": [{"id": "p-new", "name": "New User", "phone": "222", "photoPath": ""}],
            "entries": [{"id": "e-new", "person": "New User", "personId": "p-new", "amount": 25000, "kind": "LENT", "note": "New Loan", "date": 1000}]
        }"""

        val preview = repository.previewBackup(ByteArrayInputStream(newBackupJson.toByteArray(Charsets.UTF_8)), cacheDir).getOrThrow()
        val result = repository.executeRestore(preview, filesDir)
        assertTrue(result.isSuccess)

        // Verify snapshot was created in pre_restore_snapshots
        val snapshotsDir = File(filesDir, "pre_restore_snapshots")
        assertTrue("Snapshots directory must exist", snapshotsDir.exists())
        val snapshots = snapshotsDir.listFiles { file -> file.name.startsWith("snapshot-") }
        assertNotNull(snapshots)
        assertEquals("Exactly one snapshot must be present", 1, snapshots!!.size)
        val snapshotText = snapshots[0].readText()
        assertTrue("Snapshot must contain pre-existing data", snapshotText.contains("Pre-existing User"))
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
        assertTrue(
            "Exception must be SecurityException",
            result.exceptionOrNull() is SecurityException
        )
    }

    @Test
    fun unknownApplicationIdentifierIsRejected() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_unknown_app")
        val badAppJson = """{
            "app": "NotDueo",
            "backupSchemaVersion": 2,
            "exportedAt": 1700000000000,
            "people": [],
            "entries": []
        }"""

        val result = runCatching {
            BackupManager.inspectBackup(ByteArrayInputStream(badAppJson.toByteArray(Charsets.UTF_8)), cacheDir)
        }
        assertTrue("Unknown app must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidBackupException)
    }

    @Test
    fun legacyJsonBackupImportsSafelyWithoutPhotos() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache_legacy")
        val legacyJson = """{
            "app": "Dueo",
            "backupSchemaVersion": 1,
            "exportedAt": 1690000000000,
            "people": [
                {"id": "p-leg", "name": "Legacy Person", "phone": "999", "photoPath": "/data/user/0/com.example.dueo/files/nonexistent.jpg"}
            ],
            "entries": [
                {"id": "e-leg", "person": "Legacy Person", "personId": "p-leg", "amount": 60000, "kind": "LENT", "note": "Old record", "date": 5000}
            ]
        }"""

        val preview = BackupManager.inspectBackup(ByteArrayInputStream(legacyJson.toByteArray(Charsets.UTF_8)), cacheDir)
        assertFalse("Legacy JSON must not be identified as ZIP archive", preview.isZipArchive)
        assertEquals(0, preview.photosCount)
        assertEquals(1, preview.peopleCount)
        assertEquals(1, preview.entriesCount)
    }
}

