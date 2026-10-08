package com.srrtracker.detect

import com.srrtracker.stats.PracticeStats

/**
 * What to do with a still. A frame with no die box is not a roll: the check
 * screen must not open, and nothing is logged. A face is prefilled only when
 * a real die box was read as 1–6. There is no default of 1.
 */
object CaptureDecision {
    fun dieBoxes(det: DiceDetector.Detection?): Int =
        det?.dice?.count { it.w > 0 && it.h > 0 } ?: 0

    /**
     * A pair both read as 1–6 at high confidence is logged with no tap.
     * Anything else with a die in the frame waits on the check screen.
     */
    fun autoAccept(det: DiceDetector.Detection?): Boolean {
        if (det == null || !det.ok || det.confidence != "high") return false
        if (dieBoxes(det) != 2) return false
        val a = det.d1 ?: return false
        val b = det.d2 ?: return false
        return a in 1..6 && b in 1..6
    }

    fun pickerFaces(det: DiceDetector.Detection?): Pair<Int?, Int?> {
        val dice = det?.dice?.filter { it.w > 0 && it.h > 0 }.orEmpty()
        if (dice.size != 2) return null to null
        return dice[0].count.takeIf { it in 1..6 } to dice[1].count.takeIf { it in 1..6 }
    }

    /** A roll enters the total and the SRR only when both faces were entered. */
    fun countsAsRoll(d1: Int, d2: Int, unread: Boolean): Boolean =
        !unread && d1 in 1..6 && d2 in 1..6

    /**
     * What an auto-saved camera roll stores. A session with no tag is valid.
     * The only refusal is a missing session. The tag is recorded so the throw
     * can be totaled later; it is not required to log the roll.
     */
    data class CameraSave(
        val sessionId: Long,
        val tagId: Long?,
        val leftFace: Int?,
        val rightFace: Int?,
        val isSeven: Boolean,
        val unread: Boolean,
        val source: String
    )

    fun cameraAutoSave(sessionId: Long, tagId: Long?, d1: Int, d2: Int, unread: Boolean): CameraSave? {
        if (sessionId <= 0) return null
        val faces = !unread && d1 in 1..6 && d2 in 1..6
        return CameraSave(
            sessionId = sessionId,
            tagId = tagId,
            leftFace = if (faces) d1 else null,
            rightFace = if (faces) d2 else null,
            isSeven = faces && d1 + d2 == 7,
            unread = unread || !faces,
            source = PracticeStats.SOURCE_CAMERA
        )
    }
}
