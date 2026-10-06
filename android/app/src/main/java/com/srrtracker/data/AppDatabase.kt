package com.srrtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Session::class, RollEntity::class, Tag::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun rolls(): RollDao
    abstract fun tags(): TagDao

    /** Replace practice data and keep the ids from the backup. */
    suspend fun replaceAll(file: BackupCodec.File) {
        withTransaction {
            rolls().deleteAll()
            sessions().deleteAll()
            tags().deleteAll()
            if (file.tags.isNotEmpty()) {
                tags().insertAll(
                    file.tags.map {
                        Tag(it.id, it.name, it.die1Color, it.die2Color, it.goal, it.sortOrder)
                    }
                )
            }
            if (file.sessions.isNotEmpty()) {
                sessions().insertAll(
                    file.sessions.map {
                        Session(it.id, it.name, it.startedAt, it.endedAt, it.tagId)
                    }
                )
            }
            if (file.rolls.isNotEmpty()) {
                rolls().insertAll(file.rolls.map { BackupCodec.entity(it) })
            }
            val sql = openHelper.writableDatabase
            for (table in listOf("tags", "sessions", "rolls")) {
                sql.execSQL(
                    "INSERT OR REPLACE INTO sqlite_sequence(name, seq) " +
                        "SELECT '$table', IFNULL(MAX(id), 0) FROM $table"
                )
            }
        }
    }

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rolls ADD COLUMN cropPaths TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN endedAt INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN tagId INTEGER")
                db.execSQL(
                    "UPDATE sessions SET endedAt = (SELECT MAX(ts) FROM rolls WHERE rolls.sessionId = sessions.id)"
                )
                db.execSQL("ALTER TABLE rolls ADD COLUMN leftFace INTEGER")
                db.execSQL("ALTER TABLE rolls ADD COLUMN rightFace INTEGER")
                db.execSQL("ALTER TABLE rolls ADD COLUMN isSeven INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rolls ADD COLUMN source TEXT NOT NULL DEFAULT 'camera'")
                val cursor = db.query("SELECT id, d1, d2, unread FROM rolls")
                cursor.use {
                    while (it.moveToNext()) {
                        val id = it.getLong(0)
                        val migrated = HistoryMigration.migrateRoll(
                            HistoryMigration.LegacyRoll(it.getInt(1), it.getInt(2), it.getInt(3) != 0)
                        )
                        db.execSQL(
                            "UPDATE rolls SET leftFace = ?, rightFace = ?, isSeven = ?, source = ? WHERE id = ?",
                            arrayOf(
                                migrated.leftFace,
                                migrated.rightFace,
                                if (migrated.isSeven) 1 else 0,
                                migrated.source,
                                id
                            )
                        )
                    }
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`die1Color` INTEGER NOT NULL, " +
                        "`die2Color` INTEGER NOT NULL, " +
                        "`goal` INTEGER, " +
                        "`sortOrder` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO tags (name, die1Color, die2Color, goal, sortOrder) " +
                        "VALUES ('${HistoryMigration.HARD_WAY}', ${HistoryMigration.HARD_WAY_RED}, ${HistoryMigration.HARD_WAY_RED}, NULL, 0)"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "srr.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
