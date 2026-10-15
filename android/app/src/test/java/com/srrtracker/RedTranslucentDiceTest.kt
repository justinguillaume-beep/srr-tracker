package com.srrtracker

import com.srrtracker.detect.CaptureDecision
import com.srrtracker.detect.ColoredDiceReader
import com.srrtracker.detect.ImageOps
import com.srrtracker.detect.MotionGate
import com.srrtracker.detect.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Justin's translucent red dice, from the phone at 8:24 PM and 9:01 PM.
 * The live screenshot's dice region has to count as 2. The saved still is a 6
 * on the left and a 5 on the right, including when each crop is read on its own.
 */
class RedTranslucentDiceTest {
    @Test
    fun theLiveScreenshotDiceRegionCountsAsTwo() {
        val screen = load("phone-wide-red-live.jpg")
        // Both dice, with cloth around them, cut from the 8:24 PM screen.
        val dice = ImageOps.crop(screen, 340, 1040, 420, 180)
        assertEquals(2, ColoredDiceReader.diceCount(dice))
        // The same pixels inside the wide on-screen box, then halved so each
        // die is about the 12–15px the preview showed.
        val box = ImageOps.crop(screen, 46, 771, 980, 490)
        assertEquals(2, ColoredDiceReader.diceCount(box))
        val small = ImageOps.scale(box, box.width / 2, box.height / 2)
        assertEquals("half-size wide box", 2, ColoredDiceReader.diceCount(small))
    }

    @Test
    fun theSavedStillReadsSixAndFive() {
        val still = load("phone-red-6-5.jpg")
        val det = ColoredDiceReader.read(still)
        assertEquals("faces ${det.counts} ${det.reason} ${ColoredDiceReader.lastSizeLog}", listOf(6, 5), det.counts)
        assertTrue(det.ok)
        assertEquals("high", det.confidence)
        assertTrue(CaptureDecision.autoAccept(det))
    }

    @Test
    fun eachCropReadsItsFace() {
        val left = load("phone-red-6-crop.jpg")
        val right = load("phone-red-5-crop.jpg")
        val det = ColoredDiceReader.read(pair(left, right))
        assertEquals("crops ${det.counts} ${det.reason} ${ColoredDiceReader.lastSizeLog}", listOf(6, 5), det.counts)
        assertTrue(CaptureDecision.autoAccept(det))
    }

    @Test
    fun aFlickerBetweenOneAndTwoCapturesAfterASecond() {
        val gate = MotionGate(settleMs = 1000)
        gate.running = true
        val gray = IntArray(MotionGate.W * MotionGate.H) { 140 }
        assertFalse(gate.onFrame(gray, 0L, 1).shouldCapture)
        assertFalse(gate.onFrame(gray, 200L, 2).shouldCapture)
        assertFalse(gate.onFrame(gray, 500L, 1).shouldCapture)
        assertFalse(gate.onFrame(gray, 900L, 2).shouldCapture)
        val shot = gate.onFrame(gray, 1_200L, 1)
        assertTrue(shot.shouldCapture)
        assertEquals(1, shot.diceSeen)
    }

    @Test
    fun oneDieSittingStillDoesNotCapture() {
        val gate = MotionGate(settleMs = 1000)
        gate.running = true
        val gray = IntArray(MotionGate.W * MotionGate.H) { 140 }
        var t = 0L
        repeat(8) {
            assertFalse(gate.onFrame(gray, t, 1).shouldCapture)
            t += 400L
        }
    }

    private fun pair(left: RgbImage, right: RgbImage): RgbImage {
        val gap = 48
        val pad = 24
        val w = pad + left.width + gap + right.width + pad
        val h = pad + maxOf(left.height, right.height) + pad
        val pixels = IntArray(w * h) { 0xFF8A8A88.toInt() }
        blit(pixels, w, left, pad, pad)
        blit(pixels, w, right, pad + left.width + gap, pad)
        return RgbImage(w, h, pixels)
    }

    private fun blit(dst: IntArray, dw: Int, src: RgbImage, x: Int, y: Int) {
        for (row in 0 until src.height) {
            System.arraycopy(src.pixels, row * src.width, dst, (y + row) * dw + x, src.width)
        }
    }

    private fun load(name: String): RgbImage {
        val stream = javaClass.classLoader.getResourceAsStream("real/$name")
            ?: error("missing test photo real/$name")
        return decodeJpeg(stream)
    }

    private fun decodeJpeg(stream: InputStream): RgbImage {
        val bytes = stream.readBytes()
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val buffered = imageIo.getMethod("read", InputStream::class.java)
            .invoke(null, ByteArrayInputStream(bytes))
            ?: error("could not decode jpeg")
        val type = buffered.javaClass
        val w = type.getMethod("getWidth").invoke(buffered) as Int
        val h = type.getMethod("getHeight").invoke(buffered) as Int
        val pixels = IntArray(w * h)
        type.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).invoke(buffered, 0, 0, w, h, pixels, 0, w)
        return RgbImage(w, h, pixels)
    }
}
