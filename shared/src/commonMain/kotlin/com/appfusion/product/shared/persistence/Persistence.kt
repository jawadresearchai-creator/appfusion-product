package com.appfusion.product.shared.persistence

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Entity(tableName = "document_records")
data class DocumentRecordEntity(
    @PrimaryKey val id: String,
    val title: String,
    val label: String = "",
)

@Dao
interface DocumentRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: DocumentRecordEntity)

    @Query("SELECT * FROM document_records WHERE id = :id LIMIT 1")
    suspend fun find(id: String): DocumentRecordEntity?
}

@Database(
    entities = [DocumentRecordEntity::class],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(DocumentDomainDatabaseConstructor::class)
abstract class DocumentDomainDatabase : RoomDatabase() {
    abstract fun records(): DocumentRecordDao
}

@Suppress("KotlinNoActualForExpect")
expect object DocumentDomainDatabaseConstructor : RoomDatabaseConstructor<DocumentDomainDatabase> {
    override fun initialize(): DocumentDomainDatabase
}

@Entity(tableName = "activity_records")
data class ActivityRecordEntity(
    @PrimaryKey val id: String,
    val title: String,
    val completedCount: Long,
)

@Dao
interface ActivityRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: ActivityRecordEntity)

    @Query("SELECT * FROM activity_records WHERE id = :id LIMIT 1")
    suspend fun find(id: String): ActivityRecordEntity?
}

@Database(
    entities = [ActivityRecordEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(ActivityDomainDatabaseConstructor::class)
abstract class ActivityDomainDatabase : RoomDatabase() {
    abstract fun records(): ActivityRecordDao
}

@Suppress("KotlinNoActualForExpect")
expect object ActivityDomainDatabaseConstructor : RoomDatabaseConstructor<ActivityDomainDatabase> {
    override fun initialize(): ActivityDomainDatabase
}

val DocumentMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "ALTER TABLE document_records ADD COLUMN label TEXT NOT NULL DEFAULT ''",
    )
}

val FailingDocumentMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "ALTER TABLE document_records ADD COLUMN label TEXT NOT NULL DEFAULT ''",
    )
    error("intentional persistence-probe migration failure")
}

fun buildDocumentDatabase(
    builder: RoomDatabase.Builder<DocumentDomainDatabase>,
    migration: Migration = DocumentMigration1To2,
): DocumentDomainDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .addMigrations(migration)
    .build()

fun buildActivityDatabase(
    builder: RoomDatabase.Builder<ActivityDomainDatabase>,
): ActivityDomainDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .build()
