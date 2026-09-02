package com.appfusion.product.shared.persistence

import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

suspend fun assertDomainSeparatedRoundTrip(
    documentDb: DocumentDomainDatabase,
    activityDb: ActivityDomainDatabase,
) {
    documentDb.records().put(
        DocumentRecordEntity(
            id = "document-1",
            title = "Passport",
            label = "identity",
        ),
    )
    activityDb.records().put(
        ActivityRecordEntity(
            id = "activity-1",
            title = "Renew passport",
            completedCount = 2,
        ),
    )

    val document = assertNotNull(documentDb.records().find("document-1"))
    val activity = assertNotNull(activityDb.records().find("activity-1"))
    assertEquals("identity", document.label)
    assertEquals(2L, activity.completedCount)
    assertEquals(null, documentDb.records().find("activity-1"))
    assertEquals(null, activityDb.records().find("document-1"))
}

fun seedLegacyDocumentV1(path: String) {
    val connection = BundledSQLiteDriver().open(path)
    try {
        connection.execSQL(
            "CREATE TABLE document_records (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)",
        )
        connection.execSQL(
            "INSERT INTO document_records(id, title) VALUES ('legacy-document', 'Legacy title')",
        )
        connection.execSQL("PRAGMA user_version = 1")
    } finally {
        connection.close()
    }
}

suspend fun assertSuccessfulMigration(path: String) {
    val database = buildDocumentDatabase(documentDatabaseBuilder(path))
    try {
        val migrated = assertNotNull(database.records().find("legacy-document"))
        assertEquals("Legacy title", migrated.title)
        assertEquals("", migrated.label)
    } finally {
        database.close()
    }
}

suspend fun assertFailingMigrationRollsBack(path: String) {
    val database = buildDocumentDatabase(
        documentDatabaseBuilder(path),
        FailingDocumentMigration1To2,
    )
    var failed = false
    try {
        database.records().find("legacy-document")
    } catch (_: Throwable) {
        failed = true
    } finally {
        database.close()
    }
    assertTrue(failed, "The deliberately failing migration must fail database opening")
    assertLegacyV1Intact(path)
}

private fun assertLegacyV1Intact(path: String) {
    val connection = BundledSQLiteDriver().open(path)
    try {
        val userVersion = connection.prepare("PRAGMA user_version")
        try {
            assertTrue(userVersion.step())
            assertEquals(1L, userVersion.getLong(0))
        } finally {
            userVersion.close()
        }

        val columns = connection.prepare("PRAGMA table_info(document_records)")
        var sawId = false
        var sawTitle = false
        var sawLabel = false
        try {
            while (columns.step()) {
                when (columns.getText(1)) {
                    "id" -> sawId = true
                    "title" -> sawTitle = true
                    "label" -> sawLabel = true
                }
            }
        } finally {
            columns.close()
        }
        assertTrue(sawId)
        assertTrue(sawTitle)
        assertFalse(sawLabel, "Failed migration must not leave the added column behind")

        val rows = connection.prepare(
            "SELECT COUNT(*) FROM document_records WHERE id = 'legacy-document' AND title = 'Legacy title'",
        )
        try {
            assertTrue(rows.step())
            assertEquals(1L, rows.getLong(0))
        } finally {
            rows.close()
        }
    } finally {
        connection.close()
    }
}
