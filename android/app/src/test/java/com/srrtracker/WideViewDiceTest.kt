package com.srrtracker

import com.srrtracker.detect.CaptureDecision
import com.srrtracker.detect.ColoredDiceReader
import com.srrtracker.detect.ImageOps
import com.srrtracker.detect.MotionGate
import com.srrtracker.detect.NormRect
import com.srrtracker.detect.RgbImage
import com.srrtracker.stats.PracticeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wide landing view. Dice are about 15–20px in the preview, the box covers
 * most of the frame, and the full-resolution still is what gets read.
 */
class WideViewDiceTest {
    private val wide = NormRect(0.04f, 0.06f, 0.96f, 0.94f)

    @Test
    fun smallColoredDiceInAWideBoxAreSeen() {
        assertEquals(2, ColoredDiceReader.diceCount(table(0xFFC04048.toInt(), 0xFFC04048.toInt()), wide))
        assertEquals(2, ColoredDiceReader.diceCount(table(0xFFAA8C88.toInt(), 0xFFB06068.toInt()), wide))
        assertEquals(2, ColoredDiceReader.diceCount(table(0xFF8C32A0.toInt(), 0xFF7A2896.toInt()), wide))
        assertEquals(2, ColoredDiceReader.diceCount(table(0xFFECC420.toInt(), 0xFFE0B018.toInt()), wide))
        assertEquals(1, ColoredDiceReader.diceCount(table(0xFFC04048.toInt(), null), wide))
        assertEquals(0, ColoredDiceReader.diceCount(table(null, null), wide))
    }

    @Test
    fun twoSmallDiceArmTheShutterAndTheFullStillIsRead() {
        val full = SyntheticTable.render(
            SyntheticTable.Spec(
                side = 72,
                face1 = 6,
                face2 = 4,
                red = true,
                rot1 = 8.0,
                rot2 = -14.0,
                glare = false,
                gap = 1.2,
                ox = 780,
                oy = 460,
                label = "wide red 6+4",
                greyCloth = true,
                pip1 = "white"
            ),
            width = 2160,
            height = 1215,
            seed = 4
        )
        val preview = ImageOps.scale(full.image, 540, 304)
        val seen = ColoredDiceReader.diceCount(preview, wide)
        assertEquals(2, seen)

        val gate = MotionGate(settleMs = 400)
        gate.running = true
        gate.frame = wide
        val gray = IntArray(MotionGate.W * MotionGate.H) { 140 }
        val first = gate.onFrame(gray, 1_000L, seen)
        assertFalse(first.shouldCapture)
        assertEquals(2, first.diceSeen)
        val shot = gate.onFrame(gray, 1_500L, seen)
        assertTrue(shot.shouldCapture)
        assertEquals(2, shot.diceSeen)

        val quiet = MotionGate(settleMs = 400)
        quiet.running = true
        val none = quiet.onFrame(gray, 2_000L, 0)
        val later = quiet.onFrame(gray, 4_000L, 0)
        assertFalse(none.shouldCapture)
        assertFalse(later.shouldCapture)
        assertEquals(0, later.diceSeen)

        val det = ColoredDiceReader.read(full.image)
        assertTrue(
            "full still ${det.reason} ${ColoredDiceReader.lastSizeLog}",
            CaptureDecision.autoAccept(det)
        )
        assertEquals(listOf(6, 4), det.counts)

        val untagged = CaptureDecision.cameraAutoSave(5L, null, det.d1!!, det.d2!!, unread = false)
        val tagged = CaptureDecision.cameraAutoSave(8L, 3L, 3, 4, unread = false)
        assertNotNull(untagged)
        assertNull(untagged!!.tagId)
        assertNotNull(tagged)
        assertEquals(3L, tagged!!.tagId)
        assertTrue(tagged.isSeven)
        assertNull(CaptureDecision.cameraAutoSave(0L, null, 6, 4, unread = false))

        val unread = CaptureDecision.cameraAutoSave(5L, null, 0, 0, unread = true)
        assertNotNull(unread)
        assertFalse(counts(unread!!))
        assertTrue(counts(untagged))
        assertTrue(counts(tagged))

        val rolls = listOf(
            untagged.sessionId to entry(untagged),
            tagged.sessionId to entry(tagged)
        )
        val sessions = mapOf(untagged.sessionId to untagged.tagId, tagged.sessionId to tagged.tagId)
        val onTag = PracticeStats.tagTotal(3L, 5_000, sessions, rolls)
        assertEquals(1, onTag.throws)
        assertEquals(1, onTag.sevens)
        val changedGoal = PracticeStats.tagTotal(3L, 100, sessions, rolls)
        assertEquals(onTag.throws, changedGoal.throws)
        val noTag = PracticeStats.tagTotal(9L, 5_000, sessions, rolls)
        assertEquals(0, noTag.throws)
    }

    private fun counts(save: CaptureDecision.CameraSave): Boolean = PracticeStats.counts(entry(save))

    private fun entry(save: CaptureDecision.CameraSave) = PracticeStats.Entry(
        save.source,
        save.unread,
        save.leftFace,
        save.rightFace,
        save.isSeven
    )

    private fun table(first: Int?, second: Int?): RgbImage {
        val w = 1600
        val h = 900
        val pixels = IntArray(w * h) { 0xFF969490.toInt() }
        val x0 = (wide.left * w).toInt()
        val x1 = (wide.right * w).toInt()
        val y1 = (wide.bottom * h).toInt()
        for (y in (y1 - 48) until y1) {
            for (x in x0 until x1) pixels[y * w + x] = 0xFF1E786C.toInt()
        }
        if (first != null) stamp(pixels, w, 480, 280, 16, first)
        if (second != null) stamp(pixels, w, 980, 360, 18, second)
        return RgbImage(w, h, pixels)
    }

    private fun stamp(pixels: IntArray, w: Int, x: Int, y: Int, side: Int, color: Int) {
        for (yy in y until y + side) {
            for (xx in x until x + side) pixels[yy * w + xx] = color
        }
    }
}
