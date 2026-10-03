package com.srrtracker

import com.srrtracker.stats.SrrStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrrStatsTest {
    private fun totals(vararg values: Int) = values.toList()

    @Test
    fun ratioAndRunningMatchTheWebApp() {
        assertEquals("1:6.0", SrrStats.fmtRatio(12, 2))
        assertEquals("\u2014", SrrStats.fmtRatio(5, 0))
        assertEquals("\u2014", SrrStats.fmtRatio(0, 0))
        val running = SrrStats.running(totals(7, 5, 7, 8)).map { it.ratio }
        assertEquals(listOf("1:1.0", "1:2.0", "1:1.5", "1:2.0"), running)
        val summary = SrrStats.summary(totals(7, 2, 3, 4, 5, 6))
        assertEquals("1:6.0", summary.ratio)
        assertEquals("0.167", summary.perRollStr)
        assertEquals("16.7%", summary.pct)
        assertEquals(1, summary.sevens)
        assertEquals(0, SrrStats.summary(emptyList()).rolls)
        assertEquals("\u2014", SrrStats.summary(emptyList()).pct)
    }

    @Test
    fun removingASevenChangesTheRunningRatio() {
        val before = SrrStats.summary(totals(7, 8, 9))
        val after = SrrStats.summary(totals(8, 9))
        assertEquals("1:3.0", before.ratio)
        assertEquals("\u2014", after.ratio)
        assertEquals(0, after.sevens)
    }

    @Test
    fun csvEscapesAndCountsSevens() {
        val csv = SrrStats.toCSV(
            listOf(
                SrrStats.CsvSession(
                    name = "a,\"b\"",
                    rolls = listOf(
                        SrrStats.CsvRoll(ts = 0, d1 = 3, d2 = 4, total = 7),
                        SrrStats.CsvRoll(ts = 1000, d1 = 1, d2 = 1, total = 2, corrected = true, hasPhoto = true)
                    )
                )
            )
        )
        val lines = csv.trim().split("\r\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("session,roll_no,timestamp_iso"))
        assertTrue(lines[1].startsWith("\"a,\"\"b\"\"\",1,"))
        assertTrue(lines[1].contains(",7,1,1,"))
        assertTrue(lines[2].contains(",2,0,1,"))
        assertTrue(csv.endsWith("\r\n"))
    }
}
