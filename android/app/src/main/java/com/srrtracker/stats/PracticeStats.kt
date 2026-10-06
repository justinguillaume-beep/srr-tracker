package com.srrtracker.stats

/**
 * What counts toward SRR. A throw counts when it is a finished roll: a camera
 * or manual entry with both faces, or a quick entry. An unread camera row does
 * not count. SRR itself uses only the seven flag and the throw count.
 * Quick entries never grow faces here.
 */
object PracticeStats {
    const val SOURCE_CAMERA = "camera"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_QUICK = "quick"

    data class Entry(
        val source: String,
        val unread: Boolean,
        val leftFace: Int?,
        val rightFace: Int?,
        val isSeven: Boolean
    )

    data class TagTotal(
        val throws: Int,
        val sevens: Int,
        val ratio: String,
        val goal: Int?
    )

    fun counts(entry: Entry): Boolean {
        if (entry.unread) return false
        if (entry.source == SOURCE_QUICK) return true
        return entry.leftFace in 1..6 && entry.rightFace in 1..6
    }

    /** Faces stay empty on a quick entry. A seven is only the flag. */
    fun quick(isSeven: Boolean) = Entry(
        source = SOURCE_QUICK,
        unread = false,
        leftFace = null,
        rightFace = null,
        isSeven = isSeven
    )

    fun summary(entries: List<Entry>): SrrStats.Summary {
        val totals = entries.filter { counts(it) }.map { if (it.isSeven) 7 else 0 }
        return SrrStats.summary(totals)
    }

    fun running(entries: List<Entry>): List<SrrStats.Running> {
        val totals = entries.filter { counts(it) }.map { if (it.isSeven) 7 else 0 }
        return SrrStats.running(totals)
    }

    fun tagTotal(
        tagId: Long,
        goal: Int?,
        sessionTag: Map<Long, Long?>,
        rolls: List<Pair<Long, Entry>>
    ): TagTotal {
        val sessions = sessionTag.filterValues { it == tagId }.keys
        val mine = rolls.filter { (sid, entry) -> sid in sessions && counts(entry) }
        val sevens = mine.count { it.second.isSeven }
        return TagTotal(
            throws = mine.size,
            sevens = sevens,
            ratio = SrrStats.fmtRatio(mine.size, sevens),
            goal = goal
        )
    }

    fun formatDuration(startedAt: Long, endedAt: Long?): String {
        val ms = ((endedAt ?: startedAt) - startedAt).coerceAtLeast(0L)
        val minutes = ms / 60_000L
        if (minutes < 1L) return "under 1 min"
        if (minutes < 60L) return "$minutes min"
        val hours = minutes / 60L
        val rest = minutes % 60L
        return if (rest == 0L) "$hours h" else "$hours h $rest min"
    }

    fun formatCount(n: Int): String = String.format(java.util.Locale.US, "%,d", n)
}
