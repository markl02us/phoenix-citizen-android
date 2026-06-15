package com.phoenix.citizen.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// v2 (2026-06-14): ReportEntity gains accuracyM + locationSource (location
// provenance). fallbackToDestructiveMigration handles the local cache rebuild —
// the app is pre-launch so there is no queued real report to preserve.
@Database(entities = [ReportEntity::class], version = 2, exportSchema = false)
abstract class PhoenixDatabase : RoomDatabase() {
    abstract fun reportDao(): ReportDao

    companion object {
        private const val DB_NAME = "phoenix-citizen.db"

        @Volatile
        private var INSTANCE: PhoenixDatabase? = null

        fun get(context: Context): PhoenixDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhoenixDatabase::class.java,
                    DB_NAME
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
