package com.srrtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): Session?

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    suspend fun all(): List<Session>

    @Delete
    suspend fun delete(session: Session)
}

@Dao
interface RollDao {
    @Query("SELECT * FROM rolls WHERE sessionId = :sid ORDER BY ts ASC, id ASC")
    fun observe(sid: Long): Flow<List<RollEntity>>

    @Query("SELECT * FROM rolls WHERE sessionId = :sid ORDER BY ts ASC, id ASC")
    suspend fun list(sid: Long): List<RollEntity>

    @Query("SELECT * FROM rolls WHERE id = :id")
    suspend fun get(id: Long): RollEntity?

    @Insert
    suspend fun insert(roll: RollEntity): Long

    @Update
    suspend fun update(roll: RollEntity)

    @Delete
    suspend fun delete(roll: RollEntity)
}
