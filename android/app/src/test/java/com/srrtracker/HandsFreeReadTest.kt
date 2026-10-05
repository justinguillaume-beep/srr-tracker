package com.srrtracker

import com.srrtracker.detect.CaptureDecision
import com.srrtracker.detect.ColoredDiceReader
import com.srrtracker.detect.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corner pips on a translucent die sit close to the rim. A 6 and a 4 like that
 * are still a roll. A face that collapsed into a 2 and a 1 is not.
 */
class HandsFreeReadTest {
    @Test
    fun rimPipsOnASixAndAFourAreLoggedWithoutATap() {
        val image = scene()
        paintDie(image, 70, 60, 140, four = true)
        paintDie(image, 420, 70, 140, four = false)
        val det = ColoredDiceReader.read(image)
        val faces = det.dice.joinToString(",") { it.count.toString() }
        println("rim faces=$faces ok=${det.ok} ${det.confidence} ${det.reason} ${ColoredDiceReader.lastSizeLog}")
        assertEquals("faces=$faces", 2, det.dice.size)
        assertEquals(setOf(6, 4), det.counts?.toSet())
        assertTrue(det.ok)
        assertEquals("high", det.confidence)
        assertTrue(CaptureDecision.autoAccept(det))
    }

    @Test
    fun aCollapsedSixIsNotLoggedAsATwo() {
        val image = scene()
        // Two wide blobs, the way a saturated six collapses, plus a centered pip.
        paintBody(image, 70, 60, 140)
        blob(image, 70 + (140 * 0.21f).toInt(), 60 + 70, 16)
        blob(image, 70 + (140 * 0.88f).toInt(), 60 + 70, 16)
        paintBody(image, 420, 70, 140)
        blob(image, 420 + 70, 70 + 70, 14)
        val det = ColoredDiceReader.read(image)
        println("collapsed counts=${det.counts} ok=${det.ok} ${det.confidence} ${det.reason} ${ColoredDiceReader.lastSizeLog}")
        assertEquals(2, det.dice.size)
        assertFalse(CaptureDecision.autoAccept(det))
    }

    private fun scene(): RgbImage {
        val w = 900
        val h = 480
        return RgbImage(w, h, IntArray(w * h) { 0xFF8A8A88.toInt() })
    }

    private fun paintDie(image: RgbImage, x: Int, y: Int, side: Int, four: Boolean) {
        paintBody(image, x, y, side)
        if (four) {
            for (px in floatArrayOf(0.08f, 0.90f)) {
                for (py in floatArrayOf(0.08f, 0.90f)) {
                    blob(image, x + (side * px).toInt(), y + (side * py).toInt(), 8)
                }
            }
        } else {
            for (px in floatArrayOf(0.10f, 0.90f)) {
                for (py in floatArrayOf(0.08f, 0.50f, 0.90f)) {
                    blob(image, x + (side * px).toInt(), y + (side * py).toInt(), 8)
                }
            }
        }
    }

    private fun paintBody(image: RgbImage, x: Int, y: Int, side: Int) {
        val purple = 0xFF9632B4.toInt()
        for (yy in y until y + side) {
            if (yy !in 0 until image.height) continue
            val row = yy * image.width
            for (xx in x until x + side) {
                if (xx !in 0 until image.width) continue
                image.pixels[row + xx] = purple
            }
        }
    }

    private fun blob(image: RgbImage, cx: Int, cy: Int, r: Int) {
        val r2 = r * r
        for (yy in cy - r..cy + r) {
            if (yy !in 0 until image.height) continue
            val row = yy * image.width
            for (xx in cx - r..cx + r) {
                if (xx !in 0 until image.width) continue
                val dx = xx - cx
                val dy = yy - cy
                if (dx * dx + dy * dy <= r2) image.pixels[row + xx] = 0xFFFFFFFF.toInt()
            }
        }
    }
}
