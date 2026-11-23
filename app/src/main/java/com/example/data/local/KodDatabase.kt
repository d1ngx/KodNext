package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SyncRuleEntity::class,
        SyncSnapshotEntity::class,
        TransferTaskEntity::class,
        CachedDirectoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class KodDatabase : RoomDatabase() {
    abstract fun syncRuleDao(): SyncRuleDao
    abstract fun syncSnapshotDao(): SyncSnapshotDao
    abstract fun transferTaskDao(): TransferTaskDao
    abstract fun directoryCacheDao(): DirectoryCacheDao

    companion object {
        private const val DB_NAME = "kodnext_local.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sync_rules ADD COLUMN conflictPolicy TEXT NOT NULL DEFAULT 'KEEP_BOTH_CONFLICT_COPY'"
                )
                db.execSQL(
                    "ALTER TABLE sync_rules ADD COLUMN ignoreHiddenFiles INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE transfer_tasks ADD COLUMN completedChunks INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE transfer_tasks ADD COLUMN chunkSizeBytes INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_snapshots` (
                        `ruleId` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `localSize` INTEGER NOT NULL,
                        `localModifiedMs` INTEGER NOT NULL,
                        `remoteSize` INTEGER NOT NULL,
                        `remoteModifiedMs` INTEGER NOT NULL,
                        `remoteHashMd5` TEXT NOT NULL,
                        `syncedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`ruleId`, `relativePath`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_snapshots_ruleId` ON `sync_snapshots` (`ruleId`)"
                )
            }
        }

        fun build(context: Context): KodDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                KodDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
