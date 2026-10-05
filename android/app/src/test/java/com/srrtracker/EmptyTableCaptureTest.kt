package com.srrtracker

import com.srrtracker.detect.CameraBox
import com.srrtracker.detect.CaptureDecision
import com.srrtracker.detect.ColoredDiceReader
import com.srrtracker.detect.DiceDetector
import com.srrtracker.detect.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyTableCaptureTest {
    @Test
    fun aTealRailOnGreyClothIsNotAPairOfDice() {
        val table = scene(rail = true, dice = false)
        assertEquals(0, ColoredDiceReader.diceCount(table, CameraBox.asRect()))
        assertEquals(0, ColoredDiceReader.diceCount(table))
    }

    @Test
    fun twoPurpleDiceCountEvenWhenTheRailIsInTheBox() {
        val table = scene(rail = true, dice = true)
        assertEquals(2, ColoredDiceReader.diceCount(table, CameraBox.asRect()))
    }

    @Test
    fun oneDieDoesNotArmACapture() {
        val table = scene(rail = false, dice = true, second = false)
        assertEquals(1, ColoredDiceReader.diceCount(table, CameraBox.asRect()))
    }

    @Test
    fun anEmptyReadDoesNotPrefillThePickersOrCountInTheSrr() {
        val guessed = DiceDetector.Detection(
            ok = true,
            total = 2,
            counts = listOf(1, 1),
            confidence = "low",
            cost = 0.05,
            margin = 0.1,
            pips = emptyList(),
            hint = "Pips look very small."
        )
        assertEquals(0, CaptureDecision.dieBoxes(guessed))
        val faces = CaptureDecision.pickerFaces(guessed)
        assertNull(faces.first)
        assertNull(faces.second)
        assertFalse(CaptureDecision.countsAsRoll(0, 0, unread = true))
        assertFalse(CaptureDecision.countsAsRoll(0, 0, unread = false))
    }

    @Test
    fun anUnreadFaceStaysBlankAndAReadFaceIsKept() {
        val det = DiceDetector.Detection(
            ok = false,
            total = null,
            counts = listOf(4, 0),
            confidence = "none",
            cost = null,
            margin = null,
            pips = emptyList(),
            dice = listOf(
                DiceDetector.DieMark(10, 10, 40, 40, 4),
                DiceDetector.DieMark(80, 10, 40, 40, 0)
            )
        )
        val faces = CaptureDecision.pickerFaces(det)
        assertEquals(4, faces.first)
        assertNull(faces.second)
        assertTrue(CaptureDecision.countsAsRoll(4, 3, unread = false))
    }

    private fun scene(rail: Boolean, dice: Boolean, second: Boolean = true): RgbImage {
        val w = 800
        val h = 450
        val pixels = IntArray(w * h) { 0xFF8C8C8A.toInt() }
        val box = CameraBox.asRect()
        val x0 = (box.left * w).toInt()
        val x1 = (box.right * w).toInt()
        val y0 = (box.top * h).toInt()
        val y1 = (box.bottom * h).toInt()
        if (rail) {
            for (y in (y1 - 40) until y1) {
                for (x in x0 until x1) pixels[y * w + x] = 0xFF1E786C.toInt()
            }
        }
        if (dice) {
            stamp(pixels, w, x0 + 48, y0 + 36, 56, 0xFF8C32A0.toInt())
            if (second) stamp(pixels, w, x1 - 104, y0 + 36, 56, 0xFF963050.toInt())
        }
        return RgbImage(w, h, pixels)
    }

    private fun stamp(pixels: IntArray, w: Int, x: Int, y: Int, side: Int, color: Int) {
        for (yy in y until y + side) {
            for (xx in x until x + side) pixels[yy * w + xx] = color
        }
    }
}
