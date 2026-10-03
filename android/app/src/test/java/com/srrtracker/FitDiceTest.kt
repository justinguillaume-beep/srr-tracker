package com.srrtracker

import com.srrtracker.detect.DiceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FitDiceTest {
    @Test
    fun perfectPipLayoutsSplitIntoTheRightFaces() {
        val side = 80.0
        val pips = ArrayList<DiceDetector.PipBlob>()
        pips += face(100.0, 120.0, 4, side, 18.0)
        pips += face(230.0, 140.0, 3, side, -12.0)
        val fit = DiceDetector.fitDice(pips)
        assertNotNull(fit)
        val counts = fit!!.groups.map { it.size }.sorted()
        assertEquals(listOf(3, 4), counts)
        assertTrue("cost ${fit.cost}", fit.cost < 0.05)
    }

    private fun face(cx: Double, cy: Double, n: Int, side: Double, rotDeg: Double): List<DiceDetector.PipBlob> {
        val a = 0.27 * side
        val points = when (n) {
            1 -> listOf(0.0 to 0.0)
            2 -> listOf(-a to -a, a to a)
            3 -> listOf(-a to -a, 0.0 to 0.0, a to a)
            4 -> listOf(-a to -a, a to -a, -a to a, a to a)
            5 -> listOf(-a to -a, a to -a, 0.0 to 0.0, -a to a, a to a)
            else -> listOf(-a to -a, a to -a, -a to 0.0, a to 0.0, -a to a, a to a)
        }
        val rad = Math.toRadians(rotDeg)
        val c = cos(rad)
        val s = sin(rad)
        return points.map { (lx, ly) ->
            val x = cx + lx * c - ly * s
            val y = cy + lx * s + ly * c
            DiceDetector.PipBlob(x, y, 180, 16.0, 50.0, 0.78, 1.0)
        }
    }
}
