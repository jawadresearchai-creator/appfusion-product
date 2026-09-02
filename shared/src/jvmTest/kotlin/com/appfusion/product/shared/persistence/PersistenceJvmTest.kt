package com.appfusion.product.shared.persistence

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PersistenceJvmTest {
    @Test
    fun domainStoresRoundTripIndependently() = runTest {
        val root = Files.createTempDirectory("appfusion-persistence-jvm")
        val documentPath = root.resolve("documents.db").toString()
        val activityPath = root.resolve("activities.db").toString()
        val documentDb = buildDocumentDatabase(documentDatabaseBuilder(documentPath))
        val activityDb = buildActivityDatabase(activityDatabaseBuilder(activityPath))
        try {
            assertDomainSeparatedRoundTrip(documentDb, activityDb)
        } finally {
            documentDb.close()
            activityDb.close()
        }
    }

    @Test
    fun versionOneDocumentStoreMigratesToVersionTwo() = runTest {
        val root = Files.createTempDirectory("appfusion-migration-jvm")
        val path = root.resolve("documents.db").toString()
        seedLegacyDocumentV1(path)
        assertSuccessfulMigration(path)
    }

    @Test
    fun failingMigrationRollsBackAtomically() = runTest {
        val root = Files.createTempDirectory("appfusion-rollback-jvm")
        val path = root.resolve("documents.db").toString()
        seedLegacyDocumentV1(path)
        assertFailingMigrationRollsBack(path)
    }
}
