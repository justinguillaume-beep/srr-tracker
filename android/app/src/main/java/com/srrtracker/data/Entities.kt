package com.srrtracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long
)

@Entity(
    tableName = "rolls",
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RollEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ts: Long,
    val d1: Int,
    val d2: Int,
    val total: Int,
    val photoPath: String?,
    val corrected: Boolean,
    val detectedD1: Int?,
    val detectedD2: Int?,
    val confidence: String?,
    val pipsJson: String?
)
