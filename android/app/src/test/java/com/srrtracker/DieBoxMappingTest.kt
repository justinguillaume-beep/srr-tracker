package com.srrtracker

import com.srrtracker.detect.CaptureFraming
import com.srrtracker.detect.ColoredDiceReader
import com.srrtracker.detect.ImageOps
import com.srrtracker.detect.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max

/**
 * A die painted at a known pixel must come back as a box on that die.
 * The 3060×1826 case is the saved still. The 3060×4080 case is the upright
 * JPEG before the preview's center crop (view 990×591, zoom 1), which is the
 * framing note on the 04dd90c check screen. Boxes are in the cropped still,
 * so a forgotten crop offset or a single long-side scale that walks off the
 * die fails here.
 */
class DieBoxMappingTest {
    @Test
    fun pinkDieOnA3060x1826StillIsInsideTheBoxAndTheCrop() {
        val dieX = 1840
        val dieY = 640
        val side = 130
        val image = scene(3060, 1826, dieX, dieY, side)
        assertBoxLandsOnDie(image, dieX, dieY, side)
    }

    @Test
    fun pinkDieOnA3060x4080CenterCropLandsInCropSpace() {
        val rawW = 3060
        val rawH = 4080
        val preview = CaptureFraming.previewOnSensor(rawW, rawH, 990, 591, zoom = 1f)
        assertEquals("center crop keeps the full width", 0, preview.x)
        assertEquals(3060, preview.w)
        assertEquals("3060x1826 from 3060x4080", 1826, preview.h)
        assertTrue("crop y ${preview.y}", preview.y in 1000..1200)

        val side = 130
        val dieX = 1720
        val dieYInCrop = 480
        val dieY = preview.y + dieYInCrop
        val full = scene(rawW, rawH, dieX, dieY, side)
        val cropped = ImageOps.crop(full, preview.x, preview.y, preview.w, preview.h)
        assertEquals(3060, cropped.width)
        assertEquals(1826, cropped.height)

        assertBoxLandsOnDie(cropped, dieX, dieYInCrop, side)
        val box = ColoredDiceReader.read(cropped).dice.single()
        assertTrue(
            "box y ${box.y} is still in the uncropped 4080 frame (die was at $dieY)",
            box.y + box.h < preview.y
        )
    }

    @Test
    fun amberStaysADieAndIvoryIsNotStolen() {
        val w = 900
        val h = 700
        val pixels = IntArray(w * h)
        fillRowGrey(pixels, w, h)
        // Ivory, yellow margin about 46. Must not become a die.
        rect(pixels, w, h, 80, 80, 100, 100, 226, 206, 160)
        // Amber, yellow margin well above 48.
        val ax = 480
        val ay = 260
        val side = 90
        rect(pixels, w, h, ax, ay, side, side, 220, 180, 40)
        val det = ColoredDiceReader.read(RgbImage(w, h, pixels))
        val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}" }
        assertEquals("ivory must not be a die: $boxes", 1, det.dice.size)
        val box = det.dice.single()
        val cx = ax + side / 2
        val cy = ay + side / 2
        assertTrue(
            "amber center ($cx,$cy) missed by $boxes",
            cx in box.x until box.x + box.w && cy in box.y until box.y + box.h
        )
        assertTrue("ivory was boxed: $boxes", box.x > 200)
    }

    private fun assertBoxLandsOnDie(image: RgbImage, dieX: Int, dieY: Int, side: Int) {
        val det = ColoredDiceReader.read(image)
        val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
        assertEquals("expected the pink die only, got $boxes reason=${det.reason}", 1, det.dice.size)
        val box = det.dice.single()
        val cx = dieX + side / 2
        val cy = dieY + side / 2
        assertTrue(
            "die center ($cx,$cy) is outside box ${box.x},${box.y} ${box.w}x${box.h}",
            cx in box.x until box.x + box.w && cy in box.y until box.y + box.h
        )
        val bx = box.x + box.w / 2
        val by = box.y + box.h / 2
        assertTrue(
            "box center ($bx,$by) is offset from die ($cx,$cy)",
            abs(bx - cx) <= 16 && abs(by - cy) <= 16
        )
        var inside = 0
        val total = side * side
        for (y in dieY until dieY + side) {
            for (x in dieX until dieX + side) {
                if (x >= box.x && y >= box.y && x < box.x + box.w && y < box.y + box.h) inside++
            }
        }
        assertTrue("box holds $inside/$total of the die square", inside >= (total * 0.90).toInt())

        // Same pad the check screen uses for the enlarged crop.
        val pad = (max(box.w, box.h) * 0.5f).toInt().coerceAtLeast(8)
        val cropX = (box.x - pad).coerceAtLeast(0)
        val cropY = (box.y - pad).coerceAtLeast(0)
        val cropW = (box.w + pad * 2).coerceAtMost(image.width - cropX)
        val cropH = (box.h + pad * 2).coerceAtMost(image.height - cropY)
        val crop = ImageOps.crop(image, cropX, cropY, cropW, cropH)
        val bodyX = dieX + 4
        val bodyY = dieY + 4
        assertTrue(
            "enlarged crop missed the die body",
            bodyX in cropX until cropX + crop.width && bodyY in cropY until cropY + crop.height
        )
        val p = crop.pixels[(bodyY - cropY) * crop.width + (bodyX - cropX)]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        assertTrue("enlarged crop is grey fabric rgb=$r,$g,$b", r - maxOf(g, b) > 32 && r > 140)

        var inCrop = 0
        for (y in dieY until dieY + side) {
            for (x in dieX until dieX + side) {
                if (x >= cropX && y >= cropY && x < cropX + cropW && y < cropY + cropH) inCrop++
            }
        }
        assertTrue("enlarged crop holds $inCrop/$total die pixels", inCrop >= (total * 0.95).toInt())
    }

    /** Grey cloth, a teal foam block, and one pink die with a white pip. */
    private fun scene(w: Int, h: Int, dieX: Int, dieY: Int, side: Int): RgbImage {
        val pixels = IntArray(w * h)
        fillRowGrey(pixels, w, h)
        rect(pixels, w, h, 240, 180, 260, 100, 42, 128, 116)
        rect(pixels, w, h, dieX, dieY, side, side, 172, 86, 74)
        val pip = (side / 6).coerceAtLeast(8)
        rect(
            pixels, w, h,
            dieX + side / 2 - pip / 2,
            dieY + side / 2 - pip / 2,
            pip, pip,
            246, 246, 242
        )
        return RgbImage(w, h, pixels)
    }

    private fun fillRowGrey(pixels: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val n = (y % 5) - 2
            val color = rgb(112 + n, 108 + n, 102 + n)
            Arrays.fill(pixels, y * w, (y + 1) * w, color)
        }
    }

    private fun rect(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, rw: Int, rh: Int, r: Int, g: Int, b: Int) {
        val color = rgb(r, g, b)
        val x0 = x.coerceIn(0, w)
        val y0 = y.coerceIn(0, h)
        val x1 = (x + rw).coerceIn(x0, w)
        val y1 = (y + rh).coerceIn(y0, h)
        for (yy in y0 until y1) {
            Arrays.fill(pixels, yy * w + x0, yy * w + x1, color)
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
