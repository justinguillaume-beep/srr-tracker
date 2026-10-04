package com.srrtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Session::class, RollEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun rolls(): RollDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rolls ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rolls ADD COLUMN readReason TEXT")
                db.execSQL("ALTER TABLE rolls ADD COLUMN debugPath TEXT")
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "srr.db"
                ).addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
