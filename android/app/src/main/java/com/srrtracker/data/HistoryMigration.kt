package com.srrtracker.data

import com.srrtracker.stats.PracticeStats

/**
 * How a roll stored before tags existed is read after the upgrade.
 * The database migration calls [migrateRoll] for each existing row.
 * Untagged sessions are left with a null tag. Nothing is deleted.
 */
object HistoryMigration {
    const val HARD_WAY = "Hard Way"
    const val HARD_WAY_RED = 0xFFFF3B3B.toInt()

    data class LegacyRoll(val d1: Int, val d2: Int, val unread: Boolean)

    data class MigratedRoll(
        val leftFace: Int?,
        val rightFace: Int?,
        val isSeven: Boolean,
        val source: String
    )

    fun migrateRoll(old: LegacyRoll): MigratedRoll {
        val faces = !old.unread && old.d1 in 1..6 && old.d2 in 1..6
        return if (!faces) {
            MigratedRoll(null, null, false, PracticeStats.SOURCE_CAMERA)
        } else {
            MigratedRoll(old.d1, old.d2, old.d1 + old.d2 == 7, PracticeStats.SOURCE_CAMERA)
        }
    }
}
