package com.appfusion.product.shared.vault

import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.EntityDomain
import com.appfusion.product.shared.EntityRef
import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.SecureBlobMetadata
import com.appfusion.product.shared.security.DeviceKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.security.unwrapWithRawAesKey
import com.appfusion.product.shared.security.wrapWithRawAesKey
import com.appfusion.product.shared.storage.BlobRecoveryReport
import com.appfusion.product.shared.storage.RecoverableSecureBlobStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class BackupContractKeyWrapper(
    private val seed: Int,
    override val keyId: String,
) : DeviceKeyWrapper {
    private val rawKey = ByteArray(32) { (it + seed).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

private class BackupMemoryMetadataStore : DocumentMetadataStore, DocumentStartupMetadataSource {
    private val records = mutableMapOf<String, VaultDocumentMetadata>()
    var failNextPut = false

    override suspend fun find(id: String): VaultDocumentMetadata? = records[id]

    override suspend fun put(metadata: VaultDocumentMetadata) {
        if (failNextPut) {
            failNextPut = false
            error("intentional backup metadata failure")
        }
        records[metadata.id] = metadata
    }

    override suspend fun listActive(): List<VaultDocumentMetadata> =
        records.values.filter { it.lifecycle == DocumentLifecycle.ACTIVE }.sortedBy { it.id }

    override suspend fun listAll(): List<VaultDocumentMetadata> = records.values.sortedBy { it.id }

    override suspend fun delete(id: String): Boolean = records.remove(id) != null
}

private class BackupMemoryBlobStore : RecoverableSecureBlobStore {
    private val entries = mutableMapOf<String, Pair<SecureBlobMetadata, EncryptedPayload>>()

    override fun writeAtomic(metadata: SecureBlobMetadata, payload: EncryptedPayload) {
        entries[metadata.blobId] = metadata.copy() to EncryptedPayload(
            payload.ciphertext.copyOf(),
            payload.integrityTag.copyOf(),
        )
    }

    override fun read(blobId: String): Pair<SecureBlobMetadata, EncryptedPayload>? =
        entries[blobId]?.let { (metadata, payload) ->
            metadata.copy() to EncryptedPayload(payload.ciphertext.copyOf(), payload.integrityTag.copyOf())
        }

    override fun delete(blobId: String): Boolean = entries.remove(blobId) != null

    override fun recover(referencedBlobIds: Set<String>): BlobRecoveryReport {
        val orphanIds = entries.keys.filter { it !in referencedBlobIds }
        orphanIds.forEach(entries::remove)
        return BlobRecoveryReport(
            interruptedWritesRemoved = 0,
            orphanBlobsRemoved = orphanIds.size,
            invalidBlobs = 0,
        )
    }
}

class DocumentVaultBackupRestoreContractTest {
    @Test
    fun encryptedRoundTripPreservesActiveArchivedAndIdempotentRestore() = runTest {
        val service = SecureBlobService(BackupContractKeyWrapper(17, "backup-contract-kek-v1"))
        val sourceMetadata = BackupMemoryMetadataStore()
        val sourceBlobs = BackupMemoryBlobStore()
        val sourceSearch = DocumentSearchProjection("backup-source", DocumentAccessPolicy { true })
        val sourceRepository = repository(sourceMetadata, sourceBlobs, service, sourceSearch)
        val activePlaintext = "backup-only-active-secret".encodeToByteArray()
        val archivedPlaintext = "backup-only-archived-secret".encodeToByteArray()

        sourceRepository.create(
            id = "active-document",
            title = "Passport",
            label = "identity",
            contentType = "application/pdf",
            plaintext = activePlaintext,
            occurredAtEpochMillis = 100,
        )
        sourceRepository.create(
            id = "archived-document",
            title = "Old Passport",
            label = "archive",
            contentType = "application/pdf",
            plaintext = archivedPlaintext,
            occurredAtEpochMillis = 200,
        )
        sourceRepository.archive("archived-document", occurredAtEpochMillis = 300)

        val export = coordinator(sourceMetadata, sourceBlobs, service, sourceSearch)
            .export(DocumentAccessPolicy { true })
        assertTrue(export.isClean)
        assertEquals(listOf("active-document", "archived-document"), export.records.map { it.ref.id })
        export.records.forEach { record ->
            assertFalse(record.payload.containsSlice(activePlaintext))
            assertFalse(record.payload.containsSlice(archivedPlaintext))
        }

        val destinationMetadata = BackupMemoryMetadataStore()
        val destinationBlobs = BackupMemoryBlobStore()
        val destinationSearch = DocumentSearchProjection("backup-destination", DocumentAccessPolicy { true })
        val destinationCoordinator = coordinator(destinationMetadata, destinationBlobs, service, destinationSearch)
        val restored = destinationCoordinator.restore(export.records)
        assertTrue(restored.isClean)
        assertEquals(2, restored.restored)
        assertEquals(0, restored.unchanged)
        assertEquals(0, restored.rejected)

        val restartedSearch = DocumentSearchProjection("backup-restarted", DocumentAccessPolicy { true })
        val startup = DocumentVaultStartupCoordinator(
            metadataSource = destinationMetadata,
            blobStore = destinationBlobs,
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

        val destinationRepository = repository(destinationMetadata, destinationBlobs, service, restartedSearch)
        assertContentEquals(
            activePlaintext,
            assertNotNull(destinationRepository.read("active-document", DocumentAccessPolicy { true })).plaintext,
        )
        assertContentEquals(
            archivedPlaintext,
            assertNotNull(destinationRepository.read("archived-document", DocumentAccessPolicy { true })).plaintext,
        )

        val idempotent = destinationCoordinator.restore(export.records)
        assertTrue(idempotent.isClean)
        assertEquals(0, idempotent.restored)
        assertEquals(2, idempotent.unchanged)
        assertEquals(0, idempotent.rejected)
    }

    @Test
    fun corruptWrongDomainAndWrongKeyRecordsFailClosedBeforeMutation() = runTest {
        val sourceService = SecureBlobService(BackupContractKeyWrapper(23, "backup-source-kek"))
        val sourceMetadata = BackupMemoryMetadataStore()
        val sourceBlobs = BackupMemoryBlobStore()
        val sourceSearch = DocumentSearchProjection("backup-corrupt-source", DocumentAccessPolicy { true })
        repository(sourceMetadata, sourceBlobs, sourceService, sourceSearch).create(
            id = "document-1",
            title = "Identity",
            label = "private",
            contentType = "application/pdf",
            plaintext = "authenticated source".encodeToByteArray(),
            occurredAtEpochMillis = 100,
        )
        val record = coordinator(sourceMetadata, sourceBlobs, sourceService, sourceSearch)
            .export(DocumentAccessPolicy { true })
            .records.single()

        val corrupt = record.copy(
            payload = record.payload.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            },
        )
        val corruptMetadata = BackupMemoryMetadataStore()
        val corruptBlobs = BackupMemoryBlobStore()
        val corruptReport = coordinator(
            corruptMetadata,
            corruptBlobs,
            sourceService,
            DocumentSearchProjection("backup-corrupt-destination", DocumentAccessPolicy { true }),
        ).restore(listOf(corrupt))
        assertEquals(1, corruptReport.rejected)
        assertTrue(corruptReport.issues.any { it.kind == DocumentVaultBackupRestoreIssueKind.DECODE_FAILED })
        assertTrue(corruptMetadata.listAll().isEmpty())

        val wrongDomain = record.copy(ref = EntityRef(EntityDomain.ACTIVITY, record.ref.id))
        val wrongDomainReport = coordinator(
            BackupMemoryMetadataStore(),
            BackupMemoryBlobStore(),
            sourceService,
            DocumentSearchProjection("backup-wrong-domain", DocumentAccessPolicy { true }),
        ).restore(listOf(wrongDomain))
        assertEquals(1, wrongDomainReport.rejected)
        assertTrue(wrongDomainReport.issues.any { it.kind == DocumentVaultBackupRestoreIssueKind.WRONG_DOMAIN })

        val wrongKeyMetadata = BackupMemoryMetadataStore()
        val wrongKeyBlobs = BackupMemoryBlobStore()
        val wrongKeyReport = coordinator(
            wrongKeyMetadata,
            wrongKeyBlobs,
            SecureBlobService(BackupContractKeyWrapper(91, "backup-wrong-kek")),
            DocumentSearchProjection("backup-wrong-key", DocumentAccessPolicy { true }),
        ).restore(listOf(record))
        assertEquals(1, wrongKeyReport.rejected)
        assertTrue(wrongKeyReport.issues.any { it.kind == DocumentVaultBackupRestoreIssueKind.AUTHENTICATION_FAILED })
        assertTrue(wrongKeyMetadata.listAll().isEmpty())
        assertNull(wrongKeyBlobs.read(DocumentBackupCodec.decode(record.payload).metadata.blobId))
    }

    @Test
    fun duplicatesAndRevisionConflictsAreDeterministic() = runTest {
        val service = SecureBlobService(BackupContractKeyWrapper(31, "backup-conflict-kek"))
        val sourceMetadata = BackupMemoryMetadataStore()
        val sourceBlobs = BackupMemoryBlobStore()
        val sourceSearch = DocumentSearchProjection("backup-conflict-source", DocumentAccessPolicy { true })
        repository(sourceMetadata, sourceBlobs, service, sourceSearch).create(
            id = "document-1",
            title = "Incoming",
            label = "source",
            contentType = "application/pdf",
            plaintext = "incoming revision one".encodeToByteArray(),
            occurredAtEpochMillis = 100,
        )
        val record = coordinator(sourceMetadata, sourceBlobs, service, sourceSearch)
            .export(DocumentAccessPolicy { true })
            .records.single()

        val duplicateReport = coordinator(
            BackupMemoryMetadataStore(),
            BackupMemoryBlobStore(),
            service,
            DocumentSearchProjection("backup-duplicates", DocumentAccessPolicy { true }),
        ).restore(listOf(record, record))
        assertEquals(2, duplicateReport.rejected)
        assertEquals(0, duplicateReport.restored)
        assertTrue(duplicateReport.issues.any { it.kind == DocumentVaultBackupRestoreIssueKind.DUPLICATE_DOCUMENT_ID })
        assertTrue(duplicateReport.issues.any { it.kind == DocumentVaultBackupRestoreIssueKind.DUPLICATE_BLOB_ID })

        val newerMetadata = BackupMemoryMetadataStore()
        val newerBlobs = BackupMemoryBlobStore()
        val newerSearch = DocumentSearchProjection("backup-local-newer", DocumentAccessPolicy { true })
        val newerRepository = repository(newerMetadata, newerBlobs, service, newerSearch)
        newerRepository.create(
            id = "document-1",
            title = "Local",
            label = "destination",
            contentType = "application/pdf",
            plaintext = "local revision one".encodeToByteArray(),
            occurredAtEpochMillis = 200,
        )
        newerRepository.update(
            id = "document-1",
            title = "Local newer",
            label = "destination",
            contentType = "application/pdf",
            plaintext = "local revision two".encodeToByteArray(),
            occurredAtEpochMillis = 300,
        )
        val newerReport = coordinator(newerMetadata, newerBlobs, service, newerSearch).restore(listOf(record))
        assertEquals(1, newerReport.rejected)
        assertTrue(newerReport.issues.any { it.kind == DocumentVaultBackupRestoreIssueKind.LOCAL_NEWER_REVISION })
        assertEquals(2L, assertNotNull(newerMetadata.find("document-1")).revision)

        val sameRevisionMetadata = BackupMemoryMetadataStore()
        val sameRevisionBlobs = BackupMemoryBlobStore()
        val sameRevisionSearch = DocumentSearchProjection("backup-same-revision", DocumentAccessPolicy { true })
        repository(sameRevisionMetadata, sameRevisionBlobs, service, sameRevisionSearch).create(
            id = "document-1",
            title = "Different local",
            label = "destination",
            contentType = "application/pdf",
            plaintext = "different same revision".encodeToByteArray(),
            occurredAtEpochMillis = 400,
        )
        val sameRevisionReport = coordinator(
            sameRevisionMetadata,
            sameRevisionBlobs,
            service,
            sameRevisionSearch,
        ).restore(listOf(record))
        assertEquals(1, sameRevisionReport.rejected)
        assertTrue(sameRevisionReport.issues.any { it.kind == DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT })
    }

    @Test
    fun metadataFailureRemovesStagedEncryptedBlobAndLeavesSearchUnchanged() = runTest {
        val service = SecureBlobService(BackupContractKeyWrapper(43, "backup-rollback-kek"))
        val sourceMetadata = BackupMemoryMetadataStore()
        val sourceBlobs = BackupMemoryBlobStore()
        val sourceSearch = DocumentSearchProjection("backup-rollback-source", DocumentAccessPolicy { true })
        repository(sourceMetadata, sourceBlobs, service, sourceSearch).create(
            id = "document-rollback",
            title = "Rollback",
            label = "private",
            contentType = "application/pdf",
            plaintext = "rollback source".encodeToByteArray(),
            occurredAtEpochMillis = 100,
        )
        val record = coordinator(sourceMetadata, sourceBlobs, service, sourceSearch)
            .export(DocumentAccessPolicy { true })
            .records.single()
        val decoded = DocumentBackupCodec.decode(record.payload)

        val destinationMetadata = BackupMemoryMetadataStore().also { it.failNextPut = true }
        val destinationBlobs = BackupMemoryBlobStore()
        val destinationSearch = DocumentSearchProjection("backup-rollback-destination", DocumentAccessPolicy { true })
        val report = coordinator(destinationMetadata, destinationBlobs, service, destinationSearch)
            .restore(listOf(record))

        assertEquals(1, report.rejected)
        assertEquals(0, report.restored)
        assertTrue(report.issues.any { it.kind == DocumentVaultBackupRestoreIssueKind.METADATA_COMMIT_FAILED })
        assertNull(destinationMetadata.find("document-rollback"))
        assertNull(destinationBlobs.read(decoded.metadata.blobId))
        assertTrue(destinationSearch.search(SearchQuery("rollback")).isEmpty())
    }

    @Test
    fun archivedUpgradeCanReuseTheSameAuthenticatedEncryptedBlob() = runTest {
        val service = SecureBlobService(BackupContractKeyWrapper(57, "backup-archive-kek"))
        val sourceMetadata = BackupMemoryMetadataStore()
        val sourceBlobs = BackupMemoryBlobStore()
        val sourceSearch = DocumentSearchProjection("backup-archive-source", DocumentAccessPolicy { true })
        val sourceRepository = repository(sourceMetadata, sourceBlobs, service, sourceSearch)
        sourceRepository.create(
            id = "document-archive",
            title = "Archive Me",
            label = "identity",
            contentType = "application/pdf",
            plaintext = "same encrypted payload".encodeToByteArray(),
            occurredAtEpochMillis = 100,
        )
        val sourceCoordinator = coordinator(sourceMetadata, sourceBlobs, service, sourceSearch)
        val activeRecord = sourceCoordinator.export(DocumentAccessPolicy { true }).records.single()

        val destinationMetadata = BackupMemoryMetadataStore()
        val destinationBlobs = BackupMemoryBlobStore()
        val destinationSearch = DocumentSearchProjection("backup-archive-destination", DocumentAccessPolicy { true })
        val destinationCoordinator = coordinator(destinationMetadata, destinationBlobs, service, destinationSearch)
        assertEquals(1, destinationCoordinator.restore(listOf(activeRecord)).restored)
        val initialBlobId = assertNotNull(destinationMetadata.find("document-archive")).blobId

        sourceRepository.archive("document-archive", occurredAtEpochMillis = 200)
        val archivedRecord = sourceCoordinator.export(DocumentAccessPolicy { true }).records.single()
        val archivedDecoded = DocumentBackupCodec.decode(archivedRecord.payload)
        assertEquals(initialBlobId, archivedDecoded.metadata.blobId)
        assertEquals(2L, archivedDecoded.metadata.revision)
        assertEquals(DocumentLifecycle.ARCHIVED, archivedDecoded.metadata.lifecycle)

        val upgrade = destinationCoordinator.restore(listOf(archivedRecord))
        assertTrue(upgrade.isClean)
        assertEquals(1, upgrade.restored)
        val restoredMetadata = assertNotNull(destinationMetadata.find("document-archive"))
        assertEquals(2L, restoredMetadata.revision)
        assertEquals(DocumentLifecycle.ARCHIVED, restoredMetadata.lifecycle)
        assertEquals(initialBlobId, restoredMetadata.blobId)
        assertTrue(destinationSearch.search(SearchQuery("archive me")).isEmpty())
        assertNotNull(destinationBlobs.read(initialBlobId))
    }
}

private fun repository(
    metadata: BackupMemoryMetadataStore,
    blobs: BackupMemoryBlobStore,
    service: SecureBlobService,
    search: DocumentSearchProjection,
): DocumentVaultRepository = DocumentVaultRepository(
    metadataStore = metadata,
    blobStore = blobs,
    secureBlobService = service,
    eventLog = InMemoryAppendOnlyActivityEventLog(),
    searchProjection = search,
)

private fun coordinator(
    metadata: BackupMemoryMetadataStore,
    blobs: BackupMemoryBlobStore,
    service: SecureBlobService,
    search: DocumentSearchProjection,
): DocumentVaultBackupRestoreCoordinator = DocumentVaultBackupRestoreCoordinator(
    metadataSource = metadata,
    metadataStore = metadata,
    blobStore = blobs,
    secureBlobService = service,
    searchProjection = search,
)

private fun ByteArray.containsSlice(candidate: ByteArray): Boolean {
    if (candidate.isEmpty() || candidate.size > size) return false
    return (0..size - candidate.size).any { start ->
        candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
    }
}
