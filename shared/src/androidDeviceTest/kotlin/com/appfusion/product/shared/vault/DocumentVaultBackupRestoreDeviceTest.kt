package com.appfusion.product.shared.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.persistence.buildDocumentDatabase
import com.appfusion.product.shared.persistence.documentDatabaseBuilder
import com.appfusion.product.shared.security.DeviceKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.security.unwrapWithRawAesKey
import com.appfusion.product.shared.security.wrapWithRawAesKey
import com.appfusion.product.shared.storage.AndroidFileSecureBlobStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private class AndroidBackupRestoreKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "android-backup-restore-kek-v1"
    private val rawKey = ByteArray(32) { (it + 61).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

@RunWith(AndroidJUnit4::class)
class DocumentVaultBackupRestoreDeviceTest {
    @Test
    fun encryptedBackupRestoreSurvivesRoomAndFilesystemRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val sourceDatabaseName = "document-vault-backup-source-$suffix.db"
        val destinationDatabaseName = "document-vault-backup-destination-$suffix.db"
        val sourceBlobRoot = File(context.filesDir, "document-vault-backup-source-blobs-$suffix")
        val destinationBlobRoot = File(context.filesDir, "document-vault-backup-destination-blobs-$suffix")
        context.deleteDatabase(sourceDatabaseName)
        context.deleteDatabase(destinationDatabaseName)
        val service = SecureBlobService(AndroidBackupRestoreKeyWrapper())
        val allowAll = DocumentAccessPolicy { true }

        try {
            val backupRecords = run {
                val sourceDatabase = buildDocumentDatabase(documentDatabaseBuilder(context, sourceDatabaseName))
                try {
                    val sourceMetadata = RoomDocumentMetadataStore(sourceDatabase.records())
                    val sourceBlobs = AndroidFileSecureBlobStore(sourceBlobRoot)
                    val sourceSearch = DocumentSearchProjection("android-backup-source", allowAll)
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
                        plaintext = "android backup active payload".encodeToByteArray(),
                        occurredAtEpochMillis = 100,
                    )
                    sourceRepository.create(
                        id = "archived-document",
                        title = "Old Passport",
                        label = "archive",
                        contentType = "application/pdf",
                        plaintext = "android backup archived payload".encodeToByteArray(),
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

            val destinationDatabase = buildDocumentDatabase(documentDatabaseBuilder(context, destinationDatabaseName))
            try {
                val destinationMetadata = RoomDocumentMetadataStore(destinationDatabase.records())
                val destinationBlobs = AndroidFileSecureBlobStore(destinationBlobRoot)
                val destinationSearch = DocumentSearchProjection("android-backup-destination", allowAll)
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

            val restartedDatabase = buildDocumentDatabase(documentDatabaseBuilder(context, destinationDatabaseName))
            try {
                val restartedSearch = DocumentSearchProjection("android-backup-restarted", allowAll)
                val startup = DocumentVaultStartupCoordinator(
                    metadataSource = RoomDocumentStartupMetadataSource(restartedDatabase.records()),
                    blobStore = AndroidFileSecureBlobStore(destinationBlobRoot),
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
            context.deleteDatabase(sourceDatabaseName)
            context.deleteDatabase(destinationDatabaseName)
            sourceBlobRoot.deleteRecursively()
            destinationBlobRoot.deleteRecursively()
        }
    }
}
