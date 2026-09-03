@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.appfusion.product.shared.vault

import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.persistence.buildDocumentDatabase
import com.appfusion.product.shared.persistence.documentDatabaseBuilder
import com.appfusion.product.shared.security.DeviceKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.security.unwrapWithRawAesKey
import com.appfusion.product.shared.security.wrapWithRawAesKey
import com.appfusion.product.shared.storage.AppleFileSecureBlobStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

private class AppleBackupRestoreKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "apple-backup-restore-kek-v1"
    private val rawKey = ByteArray(32) { (it + 73).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

class DocumentVaultBackupRestoreIosTest {
    @Test
    fun encryptedBackupRestoreSurvivesRoomAndFilesystemRestart() = runTest {
        val suffix = NSUUID().UUIDString
        val sourceDatabasePath = NSTemporaryDirectory() + "/document-vault-backup-source-$suffix.db"
        val destinationDatabasePath = NSTemporaryDirectory() + "/document-vault-backup-destination-$suffix.db"
        val sourceBlobRoot = NSTemporaryDirectory() + "/document-vault-backup-source-blobs-$suffix"
        val destinationBlobRoot = NSTemporaryDirectory() + "/document-vault-backup-destination-blobs-$suffix"
        val service = SecureBlobService(AppleBackupRestoreKeyWrapper())
        val allowAll = DocumentAccessPolicy { true }

        try {
            val backupRecords = run {
                val sourceDatabase = buildDocumentDatabase(documentDatabaseBuilder(sourceDatabasePath))
                try {
                    val sourceMetadata = RoomDocumentMetadataStore(sourceDatabase.records())
                    val sourceBlobs = AppleFileSecureBlobStore(sourceBlobRoot)
                    val sourceSearch = DocumentSearchProjection("ios-backup-source", allowAll)
                    val sourceRepository = DocumentVaultRepository(
                        metadataStore = sourceMetadata,
                        blobStore = sourceBlobs,
                        secureBlobService = service,
                        eventLog = InMemoryAppendOnlyActivityEventLog(),
                        searchProjection = sourceSearch,
                    )
                    sourceRepository.create(
                        id = "active-document",
                        title = "Passport",
                        label = "identity",
                        contentType = "application/pdf",
                        plaintext = "ios backup active payload".encodeToByteArray(),
                        occurredAtEpochMillis = 100,
                    )
                    sourceRepository.create(
                        id = "archived-document",
                        title = "Old Passport",
                        label = "archive",
                        contentType = "application/pdf",
                        plaintext = "ios backup archived payload".encodeToByteArray(),
                        occurredAtEpochMillis = 200,
                    )
                    sourceRepository.archive("archived-document", occurredAtEpochMillis = 300)
                    val exported = DocumentVaultBackupRestoreCoordinator(
                        metadataSource = RoomDocumentStartupMetadataSource(sourceDatabase.records()),
                        metadataStore = sourceMetadata,
                        blobStore = sourceBlobs,
                        secureBlobService = service,
                        searchProjection = sourceSearch,
                    ).export(allowAll)
                    assertTrue(exported.isClean)
                    assertEquals(2, exported.records.size)
                    exported.records
                } finally {
                    sourceDatabase.close()
                }
            }

            val destinationDatabase = buildDocumentDatabase(documentDatabaseBuilder(destinationDatabasePath))
            try {
                val destinationMetadata = RoomDocumentMetadataStore(destinationDatabase.records())
                val destinationBlobs = AppleFileSecureBlobStore(destinationBlobRoot)
                val destinationSearch = DocumentSearchProjection("ios-backup-destination", allowAll)
                val restore = DocumentVaultBackupRestoreCoordinator(
                    metadataSource = RoomDocumentStartupMetadataSource(destinationDatabase.records()),
                    metadataStore = destinationMetadata,
                    blobStore = destinationBlobs,
                    secureBlobService = service,
                    searchProjection = destinationSearch,
                ).restore(backupRecords)
                assertTrue(restore.isClean)
                assertEquals(2, restore.restored)
                assertEquals(0, restore.rejected)
            } finally {
                destinationDatabase.close()
            }

            val restartedDatabase = buildDocumentDatabase(documentDatabaseBuilder(destinationDatabasePath))
            try {
                val restartedSearch = DocumentSearchProjection("ios-backup-restarted", allowAll)
                val startup = DocumentVaultStartupCoordinator(
                    metadataSource = RoomDocumentStartupMetadataSource(restartedDatabase.records()),
                    blobStore = AppleFileSecureBlobStore(destinationBlobRoot),
                    secureBlobService = service,
                    searchProjection = restartedSearch,
                ).start()
                assertTrue(startup.isClean)
                assertEquals(1, startup.verifiedActiveDocuments)
                assertEquals(1, startup.verifiedArchivedDocuments)
                assertEquals(
                    listOf("active-document"),
                    restartedSearch.search(SearchQuery("passport")).map { it.ref.id },
                )
                assertTrue(restartedSearch.search(SearchQuery("old passport")).isEmpty())
            } finally {
                restartedDatabase.close()
            }
        } finally {
            val manager = NSFileManager.defaultManager
            listOf(sourceBlobRoot, destinationBlobRoot).forEach { path ->
                manager.removeItemAtPath(path, error = null)
            }
            listOf(sourceDatabasePath, destinationDatabasePath).forEach { path ->
                manager.removeItemAtPath(path, error = null)
                manager.removeItemAtPath(path + "-wal", error = null)
                manager.removeItemAtPath(path + "-shm", error = null)
            }
        }
    }
}
