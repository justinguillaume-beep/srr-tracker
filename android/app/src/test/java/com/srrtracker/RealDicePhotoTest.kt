package com.srrtracker

import com.srrtracker.detect.ColoredDiceReader
import com.srrtracker.detect.NormRect
import com.srrtracker.detect.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * Justin's phone photos: translucent purple dice and one amber die, white pips,
 * on grey cloth, with teal foam and clutter in the same frame.
 *
 * Ground truth:
 * 3643033e… six dice: 4, 3, 3, 2, 6, 4
 * 0cb28426… four dice: 6, 5, 3, 6
 * 0f574bd7… two dice: 2, 1
 * 156f4bdb… two dice: 6, 6
 */
class RealDicePhotoTest {
    @Test
    fun eachPhotoFindsTheRightNumberOfDiceAndIgnoresClutter() {
        val expect = linkedMapOf(
            "3643033e8104f89a148c9882316c12729b3c8719e0b411ac708b2274819fbd61.jpg" to 6,
            "0cb28426ded7371c6a8c8b65ffa75ba253b5ac3672fb5bb985bdca43728372e1.jpg" to 4,
            "0f574bd769c86927046ef7647106781fea22735124dba8dca42d31989df2044c.jpg" to 2,
            "156f4bdbb11797dee3ada222b990255054bc8d9ff4944a7c929ce83d3454df52.jpg" to 2
        )
        for ((name, n) in expect) {
            val det = ColoredDiceReader.read(load(name))
            val faces = det.dice.joinToString(",") { it.count.toString() }
            println("$name dice=${det.dice.size} ok=${det.ok} reason=${det.reason} faces=$faces counts=${det.counts}")
            assertEquals(name, n, det.dice.size)
            if (n == 2) {
                assertEquals(name, 2, det.dice.size)
            } else {
                assertFalse(name, det.ok)
                assertEquals(name, "found $n dice", det.reason)
            }
        }
    }

    @Test
    fun twoDicePhotosAreNotLoggedUnlessTheFacesMatch() {
        val cases = listOf(
            "0f574bd769c86927046ef7647106781fea22735124dba8dca42d31989df2044c.jpg" to setOf(2, 1),
            "156f4bdbb11797dee3ada222b990255054bc8d9ff4944a7c929ce83d3454df52.jpg" to setOf(6, 6)
        )
        for ((name, truth) in cases) {
            val det = ColoredDiceReader.read(load(name))
            val got = det.counts?.toSet()
            println("$name got=$got truth=$truth ok=${det.ok} ${det.confidence} ${det.reason}")
            assertEquals(name, 2, det.dice.size)
            if (got == truth) {
                assertTrue(det.ok)
                assertEquals("high", det.confidence)
            } else {
                assertFalse("refused to log $got for $truth", det.ok && det.confidence == "high")
            }
        }
    }

    @Test
    fun aBoxAroundTwoOfSixDiceDoesNotCountTheOthers() {
        val name = "3643033e8104f89a148c9882316c12729b3c8719e0b411ac708b2274819fbd61.jpg"
        val image = load(name)
        val all = ColoredDiceReader.read(image)
        assertEquals(6, all.dice.size)
        val left = all.dice.sortedBy { it.x }.take(2)
        val padX = image.width * 0.02f
        val padY = image.height * 0.02f
        val roi = NormRect(
            left = ((left.minOf { it.x } - padX) / image.width).coerceIn(0f, 1f),
            top = ((left.minOf { it.y } - padY) / image.height).coerceIn(0f, 1f),
            right = ((left.maxOf { it.x + it.w } + padX) / image.width).coerceIn(0f, 1f),
            bottom = ((left.maxOf { it.y + it.h } + padY) / image.height).coerceIn(0f, 1f)
        )
        val inside = ColoredDiceReader.read(image, roi)
        println("ROI dice=${inside.dice.size} counts=${inside.counts} ok=${inside.ok} reason=${inside.reason}")
        assertTrue("roi should hold fewer dice than the whole photo", inside.dice.size in 1..3)
        assertTrue(inside.dice.size < all.dice.size)
        val empty = ColoredDiceReader.read(image, NormRect(0.02f, 0.02f, 0.18f, 0.18f))
        assertEquals(0, empty.dice.size)
        assertEquals("found 0 dice", empty.reason)
    }

    private fun load(name: String): RgbImage {
        val stream = javaClass.classLoader.getResourceAsStream("real/$name")
            ?: error("missing test photo real/$name")
        return decodeJpeg(stream)
    }

    /** Host unit tests have javax.imageio; the Android compile stubs do not, so load it by name. */
    private fun decodeJpeg(stream: InputStream): RgbImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val buffered = imageIo.getMethod("read", InputStream::class.java).invoke(null, stream)
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
