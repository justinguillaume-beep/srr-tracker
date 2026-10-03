package com.srrtracker

import com.srrtracker.detect.DiceDetector
import com.srrtracker.detect.DiceLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dice that occupy only 40-90 px in a 1920x1080 frame. The report is printed
 * and the suite fails if accuracy drops off.
 */
class SmallDiceAccuracyTest {
    @Test
    fun closeUpFacesMatchBeforeWeTrustSmallDice() {
        for ((a, b, red) in listOf(Triple(3, 4, false), Triple(6, 1, false), Triple(2, 5, true), Triple(1, 1, false))) {
            val image = SyntheticTable.closeUp(a, b, red)
            val det = DiceDetector.detect(image, maxDim = 640)
            assertTrue(
                "close-up $a+$b red=$red -> ${det.total} ${det.counts} ${det.confidence} ${det.reason}",
                det.ok && det.total == a + b
            )
        }
    }

    @Test
    fun locatorBoxCoversBothSmallDice() {
        val spec = SyntheticTable.Spec(40, 2, 5, false, 10.0, -15.0, false, 0.5, 180, 220, "loc")
        val scene = SyntheticTable.render(spec, width = 1920, height = 1080, seed = 11)
        val found = DiceLocator.locate(scene.image)
        assertNotNull(found)
        assertTrue("roi ${found!!.rect} missed die 1 ${scene.c1}", found.rect.contains(scene.c1.first, scene.c1.second))
        assertTrue("roi ${found.rect} missed die 2 ${scene.c2}", found.rect.contains(scene.c2.first, scene.c2.second))
    }

    @Test(timeout = 240_000)
    fun smallDiceTotalsAreMostlyRight() {
        val cases = listOf(
            case(40, 1, 1, false, 8.0, -6.0, false, 0.55, 160, 200),
            case(40, 1, 6, false, 20.0, 5.0, false, 0.5, 220, 260),
            case(40, 2, 5, false, -12.0, 30.0, false, 0.45, 300, 180),
            case(40, 3, 4, false, 15.0, -25.0, true, 0.6, 180, 300),
            case(40, 6, 6, false, 4.0, 18.0, false, 0.5, 400, 240),
            case(40, 2, 2, true, 22.0, -8.0, false, 0.5, 240, 200),
            case(45, 5, 1, true, 0.0, 40.0, true, 0.4, 200, 220),
            case(55, 4, 3, false, 11.0, 17.0, false, 0.5, 260, 210),
            case(55, 6, 2, false, -30.0, 10.0, false, 0.35, 180, 250),
            case(60, 3, 6, true, 8.0, -18.0, false, 0.5, 300, 180),
            case(60, 1, 2, false, 25.0, 6.0, true, 0.55, 220, 280),
            case(70, 5, 5, false, 14.0, -14.0, false, 0.4, 200, 200),
            case(70, 4, 6, false, -5.0, 28.0, false, 0.5, 340, 220),
            case(80, 2, 4, false, 19.0, 3.0, false, 0.45, 180, 190),
            case(80, 6, 3, true, -22.0, 12.0, true, 0.4, 260, 200),
            case(90, 1, 4, false, 7.0, -9.0, false, 0.35, 200, 180),
            case(90, 5, 2, false, 33.0, 4.0, false, 0.5, 300, 210),
            case(50, 3, 3, true, 16.0, -16.0, false, 0.12, 240, 240)
        )
        val lines = ArrayList<String>()
        var ok = 0
        var exact = 0
        var high = 0
        var highOk = 0
        val bySide = LinkedHashMap<Int, IntArray>()
        for ((index, spec) in cases.withIndex()) {
            val scene = SyntheticTable.render(spec, width = 1920, height = 1080, seed = 20 + index)
            val det = DiceDetector.detectRoll(scene.image)
            val truth = spec.face1 + spec.face2
            val totalOk = det.ok && det.total == truth
            val facesOk = det.ok && det.counts != null && (
                (det.counts[0] == spec.face1 && det.counts[1] == spec.face2) ||
                    (det.counts[0] == spec.face2 && det.counts[1] == spec.face1)
                )
            if (totalOk) ok++
            if (facesOk) exact++
            if (det.confidence == "high") {
                high++
                if (totalOk) highOk++
            }
            val bucket = bySide.getOrPut(spec.side) { IntArray(2) }
            bucket[1]++
            if (totalOk) bucket[0]++
            val color = if (spec.red) "red" else "white"
            lines += "${spec.side.toString().padEnd(4)} $color ${spec.face1}+${spec.face2}=$truth -> " +
                "${det.counts?.joinToString("+") ?: "?"} = ${det.total} ${det.confidence} " +
                "cost=${det.cost?.let { "%.3f".format(it) } ?: "-"} ${if (totalOk) "OK" else "MISS"} ${det.reason ?: ""}"
        }
        val report = buildString {
            appendLine("SMALL DICE ACCURACY")
            lines.forEach { appendLine(it) }
            appendLine("by side:")
            for ((side, pair) in bySide) {
                appendLine("  ${side}px  ${pair[0]}/${pair[1]} = ${pct(pair[0], pair[1])}")
            }
            appendLine("OVERALL total $ok/${cases.size} = ${pct(ok, cases.size)}  exact faces $exact/${cases.size} = ${pct(exact, cases.size)}")
            appendLine("high-confidence $highOk/$high correct")
        }
        println(report)
        val small = bySide.filterKeys { it <= 50 }
        val smallOk = small.values.sumOf { it[0] }
        val smallN = small.values.sumOf { it[1] }
        assertTrue("\n$report", ok.toDouble() / cases.size >= 0.75)
        assertTrue("small dice (40-50px) were weak\n$report", smallN == 0 || smallOk.toDouble() / smallN >= 0.60)
        if (high > 0) {
            assertTrue("high confidence was wrong too often\n$report", highOk.toDouble() / high >= 0.90)
        }
        assertEquals(cases.size, lines.size)
    }

    private fun case(
        side: Int, a: Int, b: Int, red: Boolean, rot1: Double, rot2: Double,
        glare: Boolean, gap: Double, ox: Int, oy: Int
    ) = SyntheticTable.Spec(side, a, b, red, rot1, rot2, glare, gap, ox, oy, "$side $a+$b")

    private fun pct(ok: Int, n: Int): String = if (n == 0) "-" else "%.1f%%".format(100.0 * ok / n)
}
