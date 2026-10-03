package com.srrtracker.stats

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * SRR math shared with the web app in stats.js.
 * SRR is rolls per seven, shown as "1:6.0". A fair pair of dice is 1:6.0.
 */
object SrrStats {
    private val isoFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

    data class Running(
        val n: Int,
        val sevens: Int,
        val ratio: String,
        val perRoll: Double,
        val perRollStr: String,
        val srr: Double?
    )

    data class Summary(
        val rolls: Int,
        val sevens: Int,
        val ratio: String,
        val perRoll: Double,
        val perRollStr: String,
        val pct: String
    )

    data class CsvRoll(
        val ts: Long,
        val d1: Int?,
        val d2: Int?,
        val total: Int,
        val corrected: Boolean = false,
        val detectedD1: Int? = null,
        val detectedD2: Int? = null,
        val confidence: String? = null,
        val hasPhoto: Boolean = false
    )

    data class CsvSession(val name: String, val rolls: List<CsvRoll>)

    fun fmtRatio(rolls: Int, sevens: Int): String {
        if (sevens == 0) return "\u2014"
        return "1:" + String.format(Locale.US, "%.1f", rolls.toDouble() / sevens)
    }

    fun fmtPerRoll(rolls: Int, sevens: Int): String {
        if (rolls == 0) return "\u2014"
        return String.format(Locale.US, "%.3f", sevens.toDouble() / rolls)
    }

    fun fmtPct(rolls: Int, sevens: Int): String {
        if (rolls == 0) return "\u2014"
        return String.format(Locale.US, "%.1f%%", 100.0 * sevens / rolls)
    }

    fun running(totals: List<Int>): List<Running> {
        var sevens = 0
        return totals.mapIndexed { i, total ->
            if (total == 7) sevens++
            val n = i + 1
            Running(
                n = n,
                sevens = sevens,
                ratio = fmtRatio(n, sevens),
                perRoll = sevens.toDouble() / n,
                perRollStr = fmtPerRoll(n, sevens),
                srr = if (sevens == 0) null else n.toDouble() / sevens
            )
        }
    }

    fun summary(totals: List<Int>): Summary {
        val sevens = totals.count { it == 7 }
        val n = totals.size
        return Summary(
            rolls = n,
            sevens = sevens,
            ratio = fmtRatio(n, sevens),
            perRoll = if (n == 0) 0.0 else sevens.toDouble() / n,
            perRollStr = fmtPerRoll(n, sevens),
            pct = fmtPct(n, sevens)
        )
    }

    fun toCSV(sessions: List<CsvSession>): String {
        val head = listOf(
            "session", "roll_no", "timestamp_iso", "local_time", "die1", "die2", "total",
            "is_seven", "sevens_so_far", "running_srr_rolls_per_seven", "running_sevens_per_roll",
            "user_corrected", "detected_die1", "detected_die2", "detect_confidence", "has_photo"
        )
        val lines = ArrayList<String>()
        lines.add(head.joinToString(","))
        for (s in sessions) {
            val run = running(s.rolls.map { it.total })
            s.rolls.forEachIndexed { i, r ->
                val cols = listOf(
                    s.name,
                    (i + 1).toString(),
                    isoFmt.format(Instant.ofEpochMilli(r.ts)),
                    localStr(r.ts),
                    r.d1?.toString() ?: "",
                    r.d2?.toString() ?: "",
                    r.total.toString(),
                    if (r.total == 7) "1" else "0",
                    run[i].sevens.toString(),
                    run[i].srr?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    String.format(Locale.US, "%.4f", run[i].perRoll),
                    if (r.corrected) "1" else "0",
                    r.detectedD1?.toString() ?: "",
                    r.detectedD2?.toString() ?: "",
                    r.confidence ?: "",
                    if (r.hasPhoto) "1" else "0"
                )
                lines.add(cols.joinToString(",") { csvEscape(it) })
            }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    fun localStr(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(ts).atZone(zone)
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second
        )
    }

    private fun csvEscape(v: String): String {
        if (v.indexOfAny(charArrayOf('"', ',', '\n')) < 0) return v
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    /** Half-up helper kept for callers that mirror JS Math.round on positive values. */
    fun round1(v: Double): Long = (v * 10.0).roundToLong()
}
