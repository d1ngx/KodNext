package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SyncRuleEntity::class,
        TransferTaskEntity::class,
        CachedDirectoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KodDatabase : RoomDatabase() {
    abstract fun syncRuleDao(): SyncRuleDao
    abstract fun transferTaskDao(): TransferTaskDao
    abstract fun directoryCacheDao(): DirectoryCacheDao

    companion object {
        private const val DB_NAME = "kodnext_local.db"

        fun build(context: Context): KodDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                KodDatabase::class.java,
                DB_NAME
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
