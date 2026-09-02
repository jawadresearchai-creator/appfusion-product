package com.appfusion.product.shared.persistence

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test

class PersistenceIosTest {
    private fun databasePath(prefix: String): String =
        NSTemporaryDirectory() + "/" + prefix + "-" + NSUUID().UUIDString + ".db"

    @Test
    fun domainStoresRoundTripIndependently() = runTest {
        val documentPath = databasePath("documents")
        val activityPath = databasePath("activities")
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
        val path = databasePath("migration")
        seedLegacyDocumentV1(path)
        assertSuccessfulMigration(path)
    }

    @Test
    fun failingMigrationRollsBackAtomically() = runTest {
        val path = databasePath("rollback")
        seedLegacyDocumentV1(path)
        assertFailingMigrationRollsBack(path)
    }
}
