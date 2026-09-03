package com.appfusion.product.shared.vault

import com.appfusion.product.shared.BackupRecord
import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.EntityDomain
import com.appfusion.product.shared.EntityRef
import com.appfusion.product.shared.SecureBlobMetadata
import com.appfusion.product.shared.SecureBlobStore
import com.appfusion.product.shared.security.SecureBlobCodec
import com.appfusion.product.shared.security.SecureBlobService

enum class DocumentVaultBackupRestoreIssueKind {
    EXPORT_MISSING_BLOB,
    EXPORT_INVALID_BLOB,
    EXPORT_BLOB_METADATA_MISMATCH,
    EXPORT_ENVELOPE_METADATA_MISMATCH,
    EXPORT_INTEGRITY_MISMATCH,
    EXPORT_AUTHENTICATION_FAILED,
    WRONG_DOMAIN,
    UNSUPPORTED_SCHEMA,
    DECODE_FAILED,
    REFERENCE_MISMATCH,
    LEGACY_RECORD_NOT_RESTORABLE,
    INVALID_BLOB_REFERENCE,
    AUTHENTICATION_FAILED,
    EMPTY_DOCUMENT_PAYLOAD,
    DUPLICATE_DOCUMENT_ID,
    DUPLICATE_BLOB_ID,
    LOCAL_LEGACY_CONFLICT,
    LOCAL_NEWER_REVISION,
    REVISION_CONFLICT,
    LOCAL_BLOB_ID_CONFLICT,
    TARGET_BLOB_OCCUPIED,
    STAGE_WRITE_FAILED,
    STAGE_READBACK_FAILED,
    METADATA_COMMIT_FAILED,
}

data class DocumentVaultBackupRestoreIssue(
    val documentId: String,
    val blobId: String?,
    val kind: DocumentVaultBackupRestoreIssueKind,
)

data class DocumentVaultBackupExportReport(
    val records: List<BackupRecord>,
    val issues: List<DocumentVaultBackupRestoreIssue>,
) {
    val isClean: Boolean get() = issues.isEmpty()
}

data class DocumentVaultBackupRestoreReport(
    val restored: Int,
    val unchanged: Int,
    val rejected: Int,
    val issues: List<DocumentVaultBackupRestoreIssue>,
) {
    init {
        require(restored >= 0 && unchanged >= 0 && rejected >= 0)
    }

    val isClean: Boolean get() = rejected == 0 && issues.isEmpty()
}

class DocumentVaultBackupRestoreCoordinator(
    private val metadataSource: DocumentStartupMetadataSource,
    private val metadataStore: DocumentMetadataStore,
    private val blobStore: SecureBlobStore,
    private val secureBlobService: SecureBlobService,
    private val searchProjection: DocumentSearchProjection,
) {
    suspend fun export(accessPolicy: DocumentAccessPolicy): DocumentVaultBackupExportReport {
        val issues = mutableListOf<DocumentVaultBackupRestoreIssue>()
        val records = mutableListOf<BackupRecord>()
        metadataSource.listAll()
            .asSequence()
            .filter { it.lifecycle == DocumentLifecycle.ACTIVE || it.lifecycle == DocumentLifecycle.ARCHIVED }
            .filter { accessPolicy.canRead(it.ref) }
            .sortedBy { it.id }
            .forEach { metadata ->
                val stored = try {
                    blobStore.read(metadata.blobId)
                } catch (_: Throwable) {
                    issues += metadata.issue(DocumentVaultBackupRestoreIssueKind.EXPORT_INVALID_BLOB)
                    return@forEach
                }
                if (stored == null) {
                    issues += metadata.issue(DocumentVaultBackupRestoreIssueKind.EXPORT_MISSING_BLOB)
                    return@forEach
                }
                val verificationIssue = verifyStoredPayload(metadata, stored.first, stored.second, export = true)
                if (verificationIssue != null) {
                    issues += metadata.issue(verificationIssue)
                    return@forEach
                }
                records += BackupRecord(
                    ref = metadata.ref,
                    schemaVersion = DOCUMENT_BACKUP_RECORD_SCHEMA_VERSION,
                    payload = DocumentBackupCodec.encode(metadata, stored.first, stored.second),
                )
            }
        return DocumentVaultBackupExportReport(
            records = records,
            issues = issues.sortedIssueOrder(),
        )
    }

    suspend fun restore(records: List<BackupRecord>): DocumentVaultBackupRestoreReport {
        if (records.isEmpty()) {
            return DocumentVaultBackupRestoreReport(0, 0, 0, emptyList())
        }

        val issues = mutableListOf<DocumentVaultBackupRestoreIssue>()
        val rejectedIndices = mutableSetOf<Int>()
        val candidates = mutableListOf<RestoreCandidate>()

        fun reject(index: Int, ref: EntityRef, blobId: String?, kind: DocumentVaultBackupRestoreIssueKind) {
            rejectedIndices += index
            issues += DocumentVaultBackupRestoreIssue(ref.id, blobId, kind)
        }

        records.forEachIndexed { index, record ->
            if (record.ref.domain != EntityDomain.DOCUMENT) {
                reject(index, record.ref, null, DocumentVaultBackupRestoreIssueKind.WRONG_DOMAIN)
                return@forEachIndexed
            }
            if (record.schemaVersion != DOCUMENT_BACKUP_RECORD_SCHEMA_VERSION) {
                reject(index, record.ref, null, DocumentVaultBackupRestoreIssueKind.UNSUPPORTED_SCHEMA)
                return@forEachIndexed
            }
            val decoded = try {
                DocumentBackupCodec.decode(record.payload)
            } catch (_: Throwable) {
                reject(index, record.ref, null, DocumentVaultBackupRestoreIssueKind.DECODE_FAILED)
                return@forEachIndexed
            }
            if (decoded.metadata.ref != record.ref) {
                reject(index, record.ref, decoded.metadata.blobId, DocumentVaultBackupRestoreIssueKind.REFERENCE_MISMATCH)
                return@forEachIndexed
            }
            if (decoded.metadata.lifecycle == DocumentLifecycle.LEGACY_MIGRATION_REQUIRED) {
                reject(
                    index,
                    record.ref,
                    decoded.metadata.blobId,
                    DocumentVaultBackupRestoreIssueKind.LEGACY_RECORD_NOT_RESTORABLE,
                )
                return@forEachIndexed
            }
            if (!isBackupSafeBlobReference(decoded.metadata.blobId)) {
                reject(
                    index,
                    record.ref,
                    decoded.metadata.blobId,
                    DocumentVaultBackupRestoreIssueKind.INVALID_BLOB_REFERENCE,
                )
                return@forEachIndexed
            }
            val plaintext = try {
                secureBlobService.unprotect(
                    decoded.securePayload.ciphertext,
                    backupSecureBlobContext(decoded.metadata.blobId, decoded.metadata.contentType),
                )
            } catch (_: Throwable) {
                reject(
                    index,
                    record.ref,
                    decoded.metadata.blobId,
                    DocumentVaultBackupRestoreIssueKind.AUTHENTICATION_FAILED,
                )
                return@forEachIndexed
            }
            val emptyPayload = plaintext.isEmpty()
            plaintext.fill(0)
            if (emptyPayload) {
                reject(
                    index,
                    record.ref,
                    decoded.metadata.blobId,
                    DocumentVaultBackupRestoreIssueKind.EMPTY_DOCUMENT_PAYLOAD,
                )
                return@forEachIndexed
            }
            candidates += RestoreCandidate(index, record, decoded)
        }

        val duplicateDocumentIds = candidates
            .groupBy { it.decoded.metadata.id }
            .filterValues { it.size > 1 }
            .keys
        val duplicateBlobIds = candidates
            .groupBy { it.decoded.metadata.blobId }
            .filterValues { it.size > 1 }
            .keys
        candidates.forEach { candidate ->
            if (candidate.decoded.metadata.id in duplicateDocumentIds) {
                reject(
                    candidate.index,
                    candidate.record.ref,
                    candidate.decoded.metadata.blobId,
                    DocumentVaultBackupRestoreIssueKind.DUPLICATE_DOCUMENT_ID,
                )
            }
            if (candidate.decoded.metadata.blobId in duplicateBlobIds) {
                reject(
                    candidate.index,
                    candidate.record.ref,
                    candidate.decoded.metadata.blobId,
                    DocumentVaultBackupRestoreIssueKind.DUPLICATE_BLOB_ID,
                )
            }
        }

        val localRecords = metadataSource.listAll().sortedBy { it.id }
        val localById = localRecords.associateBy { it.id }
        val localBlobOwners = localRecords.groupBy { it.blobId }
        var restored = 0
        var unchanged = 0

        candidates
            .filter { it.index !in rejectedIndices }
            .sortedBy { it.decoded.metadata.id }
            .forEach { candidate ->
                val incoming = candidate.decoded.metadata
                val local = localById[incoming.id]

                if (local?.lifecycle == DocumentLifecycle.LEGACY_MIGRATION_REQUIRED) {
                    reject(
                        candidate.index,
                        candidate.record.ref,
                        incoming.blobId,
                        DocumentVaultBackupRestoreIssueKind.LOCAL_LEGACY_CONFLICT,
                    )
                    return@forEach
                }

                val otherBlobOwners = localBlobOwners[incoming.blobId]
                    .orEmpty()
                    .filter { it.id != incoming.id }
                if (otherBlobOwners.isNotEmpty()) {
                    reject(
                        candidate.index,
                        candidate.record.ref,
                        incoming.blobId,
                        DocumentVaultBackupRestoreIssueKind.LOCAL_BLOB_ID_CONFLICT,
                    )
                    return@forEach
                }

                if (local != null && local.revision > incoming.revision) {
                    reject(
                        candidate.index,
                        candidate.record.ref,
                        incoming.blobId,
                        DocumentVaultBackupRestoreIssueKind.LOCAL_NEWER_REVISION,
                    )
                    return@forEach
                }

                if (local != null && local.revision == incoming.revision) {
                    if (local != incoming || !existingBlobMatches(candidate.decoded)) {
                        reject(
                            candidate.index,
                            candidate.record.ref,
                            incoming.blobId,
                            DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT,
                        )
                    } else {
                        when (incoming.lifecycle) {
                            DocumentLifecycle.ACTIVE -> searchProjection.publish(incoming)
                            DocumentLifecycle.ARCHIVED -> searchProjection.remove(incoming.id)
                            DocumentLifecycle.LEGACY_MIGRATION_REQUIRED -> Unit
                        }
                        unchanged += 1
                    }
                    return@forEach
                }

                val reuseExistingBlob = local != null && local.blobId == incoming.blobId
                if (reuseExistingBlob) {
                    if (!existingBlobMatches(candidate.decoded)) {
                        reject(
                            candidate.index,
                            candidate.record.ref,
                            incoming.blobId,
                            DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT,
                        )
                        return@forEach
                    }
                } else {
                    val targetOccupied = try {
                        blobStore.read(incoming.blobId) != null
                    } catch (_: Throwable) {
                        true
                    }
                    if (targetOccupied) {
                        reject(
                            candidate.index,
                            candidate.record.ref,
                            incoming.blobId,
                            DocumentVaultBackupRestoreIssueKind.TARGET_BLOB_OCCUPIED,
                        )
                        return@forEach
                    }
                    try {
                        blobStore.writeAtomic(candidate.decoded.secureMetadata, candidate.decoded.securePayload)
                    } catch (_: Throwable) {
                        reject(
                            candidate.index,
                            candidate.record.ref,
                            incoming.blobId,
                            DocumentVaultBackupRestoreIssueKind.STAGE_WRITE_FAILED,
                        )
                        return@forEach
                    }
                    if (!existingBlobMatches(candidate.decoded)) {
                        runCatching { blobStore.delete(incoming.blobId) }
                        reject(
                            candidate.index,
                            candidate.record.ref,
                            incoming.blobId,
                            DocumentVaultBackupRestoreIssueKind.STAGE_READBACK_FAILED,
                        )
                        return@forEach
                    }
                }

                try {
                    metadataStore.put(incoming)
                } catch (_: Throwable) {
                    if (!reuseExistingBlob) runCatching { blobStore.delete(incoming.blobId) }
                    reject(
                        candidate.index,
                        candidate.record.ref,
                        incoming.blobId,
                        DocumentVaultBackupRestoreIssueKind.METADATA_COMMIT_FAILED,
                    )
                    return@forEach
                }

                if (local != null && local.blobId != incoming.blobId) {
                    runCatching { blobStore.delete(local.blobId) }
                }
                when (incoming.lifecycle) {
                    DocumentLifecycle.ACTIVE -> searchProjection.publish(incoming)
                    DocumentLifecycle.ARCHIVED -> searchProjection.remove(incoming.id)
                    DocumentLifecycle.LEGACY_MIGRATION_REQUIRED -> Unit
                }
                restored += 1
            }

        return DocumentVaultBackupRestoreReport(
            restored = restored,
            unchanged = unchanged,
            rejected = records.size - restored - unchanged,
            issues = issues.sortedIssueOrder(),
        )
    }

    private suspend fun verifyStoredPayload(
        metadata: VaultDocumentMetadata,
        secureMetadata: SecureBlobMetadata,
        securePayload: EncryptedPayload,
        export: Boolean,
    ): DocumentVaultBackupRestoreIssueKind? {
        if (secureMetadata.blobId != metadata.blobId || secureMetadata.contentType != metadata.contentType) {
            return if (export) {
                DocumentVaultBackupRestoreIssueKind.EXPORT_BLOB_METADATA_MISMATCH
            } else {
                DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT
            }
        }
        val envelope = try {
            SecureBlobCodec.decode(securePayload.ciphertext)
        } catch (_: Throwable) {
            return if (export) {
                DocumentVaultBackupRestoreIssueKind.EXPORT_INVALID_BLOB
            } else {
                DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT
            }
        }
        if (envelope.version != secureMetadata.envelopeVersion) {
            return if (export) {
                DocumentVaultBackupRestoreIssueKind.EXPORT_ENVELOPE_METADATA_MISMATCH
            } else {
                DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT
            }
        }
        if (envelope.ciphertext.size < BACKUP_AUTH_TAG_BYTES ||
            !securePayload.integrityTag.contentEquals(
                envelope.ciphertext.copyOfRange(
                    envelope.ciphertext.size - BACKUP_AUTH_TAG_BYTES,
                    envelope.ciphertext.size,
                ),
            )
        ) {
            return if (export) {
                DocumentVaultBackupRestoreIssueKind.EXPORT_INTEGRITY_MISMATCH
            } else {
                DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT
            }
        }
        val plaintext = try {
            secureBlobService.unprotect(
                securePayload.ciphertext,
                backupSecureBlobContext(metadata.blobId, metadata.contentType),
            )
        } catch (_: Throwable) {
            return if (export) {
                DocumentVaultBackupRestoreIssueKind.EXPORT_AUTHENTICATION_FAILED
            } else {
                DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT
            }
        }
        val emptyPayload = plaintext.isEmpty()
        plaintext.fill(0)
        if (emptyPayload) {
            return if (export) {
                DocumentVaultBackupRestoreIssueKind.EXPORT_AUTHENTICATION_FAILED
            } else {
                DocumentVaultBackupRestoreIssueKind.REVISION_CONFLICT
            }
        }
        return null
    }

    private suspend fun existingBlobMatches(decoded: DecodedDocumentBackup): Boolean {
        val stored = try {
            blobStore.read(decoded.metadata.blobId)
        } catch (_: Throwable) {
            return false
        } ?: return false
        if (!secureBlobMetadataEquals(stored.first, decoded.secureMetadata)) return false
        if (!encryptedPayloadEquals(stored.second, decoded.securePayload)) return false
        return verifyStoredPayload(
            decoded.metadata,
            stored.first,
            stored.second,
            export = false,
        ) == null
    }
}

private data class RestoreCandidate(
    val index: Int,
    val record: BackupRecord,
    val decoded: DecodedDocumentBackup,
)

private fun secureBlobMetadataEquals(first: SecureBlobMetadata, second: SecureBlobMetadata): Boolean =
    first.blobId == second.blobId &&
        first.envelopeVersion == second.envelopeVersion &&
        first.contentType == second.contentType

private fun encryptedPayloadEquals(first: EncryptedPayload, second: EncryptedPayload): Boolean =
    first.ciphertext.contentEquals(second.ciphertext) &&
        first.integrityTag.contentEquals(second.integrityTag)

private fun VaultDocumentMetadata.issue(kind: DocumentVaultBackupRestoreIssueKind) =
    DocumentVaultBackupRestoreIssue(id, blobId, kind)

private fun MutableList<DocumentVaultBackupRestoreIssue>.sortedIssueOrder(): List<DocumentVaultBackupRestoreIssue> =
    sortedWith(
        compareBy<DocumentVaultBackupRestoreIssue> { it.documentId }
            .thenBy { it.blobId ?: "" }
            .thenBy { it.kind.name },
    )

private fun isBackupSafeBlobReference(blobId: String): Boolean {
    if (blobId.isBlank() || blobId == "." || blobId == "..") return false
    if (blobId.encodeToByteArray().size > MAX_BACKUP_BLOB_ID_BYTES) return false
    return blobId.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }
}

private fun backupSecureBlobContext(blobId: String, contentType: String): ByteArray {
    val blobIdBytes = blobId.encodeToByteArray()
    val contentTypeBytes = contentType.encodeToByteArray()
    val secondLengthOffset = 4 + blobIdBytes.size
    return ByteArray(8 + blobIdBytes.size + contentTypeBytes.size).also { output ->
        writeBackupInt32(output, 0, blobIdBytes.size)
        blobIdBytes.copyInto(output, destinationOffset = 4)
        writeBackupInt32(output, secondLengthOffset, contentTypeBytes.size)
        contentTypeBytes.copyInto(output, destinationOffset = secondLengthOffset + 4)
    }
}

private fun writeBackupInt32(output: ByteArray, offset: Int, value: Int) {
    require(value >= 0)
    output[offset] = (value ushr 24).toByte()
    output[offset + 1] = (value ushr 16).toByte()
    output[offset + 2] = (value ushr 8).toByte()
    output[offset + 3] = value.toByte()
}

private const val DOCUMENT_BACKUP_RECORD_SCHEMA_VERSION = 1
private const val BACKUP_AUTH_TAG_BYTES = 16
private const val MAX_BACKUP_BLOB_ID_BYTES = 1024
