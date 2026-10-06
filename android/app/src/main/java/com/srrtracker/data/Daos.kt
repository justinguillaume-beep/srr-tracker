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

    @Query("UPDATE sessions SET tagId = :tagId WHERE id = :id")
    suspend fun setTag(id: Long, tagId: Long?)

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun setEnded(id: Long, endedAt: Long)

    @Query("UPDATE sessions SET tagId = NULL WHERE tagId = :tagId")
    suspend fun clearTag(tagId: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(session: Session)

    @Insert
    suspend fun insertAll(sessions: List<Session>)
}

@Dao
interface TagDao {
    @Insert
    suspend fun insert(tag: Tag): Long

    @Update
    suspend fun update(tag: Tag)

    @Delete
    suspend fun delete(tag: Tag)

    @Query("SELECT * FROM tags ORDER BY sortOrder ASC, id ASC")
    suspend fun all(): List<Tag>

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int

    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(tags: List<Tag>)
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

    @Query("DELETE FROM rolls")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(rolls: List<RollEntity>)
}
