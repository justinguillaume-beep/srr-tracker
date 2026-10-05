package com.srrtracker

import com.srrtracker.detect.ColoredDiceReader
import com.srrtracker.detect.ImageOps
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
 * 5ec3a0f2… and d1e4f113… six dice each (five purple, one gold) after the phone was moved.
 * On 5ec3a0f2 the gold face (lower left) is a diagonal 3 once extra bright spots are ignored.
 * On d1e4f113 the gold face reads 6. Other faces on those two photos are glare, shadow,
 * or a clipped box, so only the die count and those two gold reads are asserted.
 * 5b225ec2… twelve dice, every top face 4 (see the sibling .label file).
 * 8f670b88… twelve dice, every top face 6 (see the sibling .label file).
 * 5aeeae0a… twelve dice, every top face 2 (see the sibling .label file).
 * 27be9999… twelve dice, every top face 1 (see the sibling .label file).
 * e751944c… twelve dice, every top face 3 (see the sibling .label file).
 */
class RealDicePhotoTest {
    @Test
    fun twelveThreesAreAllFoundAndReadAsThree() {
        val stem = "e751944ce548f7058323a9e1ade5b502c26ab02c1715b791c80bd2d1e2cac0e5"
        val label = loadLabel("$stem.label")
        assertEquals(3, label.face)
        assertEquals(12, label.dice)
        val det = ColoredDiceReader.read(load("$stem.jpg"))
        val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
        println("THREES dice=${det.dice.size} ok=${det.ok} reason=${det.reason} boxes=$boxes")
        println("  sizes ${ColoredDiceReader.lastSizeLog}")
        assertEquals("found ${det.dice.size}: $boxes", label.dice, det.dice.size)
        val matched = det.dice.count { it.count == label.face }
        assertEquals("read as ${label.face}: $matched of ${det.dice.size} boxes=$boxes", label.dice, matched)
    }

    @Test
    fun twelveOnesAreAllFoundAndReadAsOne() {
        val stem = "27be999994e962c5a71dba88da5f25465181c781145e07d9f2565ca8b6e5234f"
        val label = loadLabel("$stem.label")
        assertEquals(1, label.face)
        assertEquals(12, label.dice)
        val det = ColoredDiceReader.read(load("$stem.jpg"))
        val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
        println("ONES dice=${det.dice.size} ok=${det.ok} reason=${det.reason} boxes=$boxes")
        println("  sizes ${ColoredDiceReader.lastSizeLog}")
        assertEquals("found ${det.dice.size}: $boxes", label.dice, det.dice.size)
        val matched = det.dice.count { it.count == label.face }
        assertEquals("read as ${label.face}: $matched of ${det.dice.size} boxes=$boxes", label.dice, matched)
    }

    @Test
    fun twelveTwosAreAllFoundAndReadAsTwo() {
        val stem = "5aeeae0a55f9affc4a840959f5442c9f4ea89014a8d937074758204500033531"
        val label = loadLabel("$stem.label")
        assertEquals(2, label.face)
        assertEquals(12, label.dice)
        val det = ColoredDiceReader.read(load("$stem.jpg"))
        val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
        println("TWOS dice=${det.dice.size} ok=${det.ok} reason=${det.reason} boxes=$boxes")
        println("  sizes ${ColoredDiceReader.lastSizeLog}")
        assertEquals("found ${det.dice.size}: $boxes", label.dice, det.dice.size)
        val matched = det.dice.count { it.count == label.face }
        assertEquals("read as ${label.face}: $matched of ${det.dice.size} boxes=$boxes", label.dice, matched)
    }

    @Test
    fun twelveSixesAreAllFoundAndReadAsSix() {
        val stem = "8f670b88fc95303a98670fb9e60479769ba241297e7686565f86aef39e9fe8cc"
        val label = loadLabel("$stem.label")
        assertEquals(6, label.face)
        assertEquals(12, label.dice)
        val det = ColoredDiceReader.read(load("$stem.jpg"))
        val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
        println("SIXES dice=${det.dice.size} ok=${det.ok} reason=${det.reason} boxes=$boxes")
        println("  sizes ${ColoredDiceReader.lastSizeLog}")
        assertEquals("found ${det.dice.size}: $boxes", label.dice, det.dice.size)
        val matched = det.dice.count { it.count == label.face }
        assertEquals("read as ${label.face}: $matched of ${det.dice.size} boxes=$boxes", label.dice, matched)
    }

    @Test
    fun twelveFoursAreAllFoundAndReadAsFour() {
        val stem = "5b225ec207de1be1e8fd2427919642fb0c7bc4b8630598af0a8f92a7eeffcce7"
        val label = loadLabel("$stem.label")
        assertEquals(4, label.face)
        assertEquals(12, label.dice)
        val det = ColoredDiceReader.read(load("$stem.jpg"))
        val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
        println("TWELVE dice=${det.dice.size} ok=${det.ok} reason=${det.reason} boxes=$boxes")
        println("  sizes ${ColoredDiceReader.lastSizeLog}")
        assertEquals("found ${det.dice.size}: $boxes", label.dice, det.dice.size)
        val matched = det.dice.count { it.count == label.face }
        assertEquals("read as ${label.face}: $matched of ${det.dice.size} boxes=$boxes", label.dice, matched)
    }

    /**
     * Writes one crop per detected die into dataset/face_N/. The face on each
     * crop is the photo's label, not the reader's count. No-op unless
     * DICE_DATASET is set, so a normal unit-test run does not touch the tree.
     * Run dataset/extract_die_crops.sh.
     */
    @Test
    fun extractsLabeledDieCrops() {
        val root = System.getenv("DICE_DATASET") ?: return
        val dataset = java.io.File(root)
        for (face in 1..6) java.io.File(dataset, "face_$face").mkdirs()
        val realDir = labeledPhotoDir()
        val labels = realDir.listFiles { f -> f.isFile && f.name.endsWith(".label") }?.sortedBy { it.name }.orEmpty()
        check(labels.isNotEmpty()) { "no .label files in ${realDir.absolutePath}" }
        val rows = ArrayList<String>()
        rows.add("path,face,source,x,y,w,h,read")
        for (labelFile in labels) {
            val stem = labelFile.name.removeSuffix(".label")
            val label = loadLabel("$stem.label")
            val image = load("$stem.jpg")
            val det = ColoredDiceReader.read(image)
            val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
            println("extract $stem label=${label.face} dice=${label.dice} found=${det.dice.size} $boxes")
            check(det.dice.size == label.dice) {
                "$stem label says ${label.dice} dice, detector found ${det.dice.size}: $boxes"
            }
            val faceDir = java.io.File(dataset, "face_${label.face}")
            faceDir.mkdirs()
            val prefix = stem.take(12)
            faceDir.listFiles()?.forEach { old ->
                if (old.name.startsWith("${prefix}_") && old.name.endsWith(".png")) old.delete()
            }
            det.dice.forEachIndexed { index, die ->
                val pad = (maxOf(die.w, die.h) * 0.10f).toInt().coerceAtLeast(4)
                val crop = ImageOps.crop(image, die.x - pad, die.y - pad, die.w + pad * 2, die.h + pad * 2)
                val name = "${prefix}_${index.toString().padStart(2, '0')}.png"
                writePng(crop, java.io.File(faceDir, name))
                rows.add("face_${label.face}/$name,${label.face},$stem.jpg,${die.x},${die.y},${die.w},${die.h},${die.count}")
            }
        }
        java.io.File(dataset, "labels.csv").writeText(rows.joinToString("\n") + "\n")
    }

    @Test
    fun eachPhotoFindsTheRightNumberOfDiceAndIgnoresClutter() {
        val expect = linkedMapOf(
            "3643033e8104f89a148c9882316c12729b3c8719e0b411ac708b2274819fbd61.jpg" to 6,
            "0cb28426ded7371c6a8c8b65ffa75ba253b5ac3672fb5bb985bdca43728372e1.jpg" to 4,
            "0f574bd769c86927046ef7647106781fea22735124dba8dca42d31989df2044c.jpg" to 2,
            "156f4bdbb11797dee3ada222b990255054bc8d9ff4944a7c929ce83d3454df52.jpg" to 2,
            "5ec3a0f20cf0684be858d21ce6f239df30adf07ee96b9a6602b1034ac7d5d35f.jpg" to 6,
            "d1e4f1133a533b6cd1bbefede8370d3ebba60c90a6974eb0a5eb893f8397ff28.jpg" to 6
        )
        for ((name, n) in expect) {
            val det = ColoredDiceReader.read(load(name))
            val faces = det.dice.joinToString(",") { it.count.toString() }
            val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
            println("$name dice=${det.dice.size} ok=${det.ok} reason=${det.reason} faces=$faces boxes=$boxes")
            println("  sizes ${ColoredDiceReader.lastSizeLog}")
            assertTrue("$name counted pips on an enlarged full-res crop", ColoredDiceReader.lastSizeLog.contains("up "))
            assertEquals(name, n, det.dice.size)
            if (name.startsWith("3643033e")) {
                assertTrue("amber die should read 6, faces=$faces", det.dice.any { it.count == 6 })
            }
            if (name.startsWith("5ec3a0f2")) {
                val gold = det.dice.single { it.x < 1200 && it.y in 2700..2950 }
                assertEquals("gold face, faces=$faces", 3, gold.count)
            }
            if (name.startsWith("d1e4f113")) {
                val gold = det.dice.single { it.x < 1200 && it.y in 2050..2400 }
                assertEquals("gold face, faces=$faces", 6, gold.count)
            }
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

    /**
     * Phone screenshots of the check screen, not the saved 3060×1826 still.
     * The still region is lifted out and the yellow overlay stroke is painted
     * back to cloth. The reader runs on that crop at the screenshot's own
     * size. Face counts are printed only. The original sensor JPEG is not
     * in the screenshot.
     */
    @Test
    fun checkScreenScreenshotsReconstructTheStillAndPrintDice() {
        val names = listOf("check-red-dice.jpg", "check-red-dice-bottom.jpg")
        for (name in names) {
            val stream = javaClass.classLoader.getResourceAsStream("ui/$name")
                ?: error("missing ui/$name")
            val ui = decodeJpeg(stream)
            println("ui $name ${ui.width}x${ui.height}")
            val whole = ColoredDiceReader.read(ui)
            println(
                "  whole dice=${whole.dice.size} reason=${whole.reason} " +
                    whole.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
            )
            val still = stillFromCheckScreen(ui)
            if (still == null) {
                println("  no still region on this screenshot")
                continue
            }
            val det = ColoredDiceReader.read(still)
            val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
            println(
                "  reconstructed ${still.width}x${still.height} dice=${det.dice.size} " +
                    "ok=${det.ok} reason=${det.reason} boxes=$boxes"
            )
            println("  sizes ${ColoredDiceReader.lastSizeLog}")
        }
    }

    /**
     * The check photo is the grey cloth on a black canvas. Cover the yellow
     * box stroke so the overlay itself is not a die.
     */
    private fun stillFromCheckScreen(ui: RgbImage): RgbImage? {
        if (ui.width < 900 || ui.height < 1600) return null
        // Text rows are mostly black, so the median stays dark. The still is grey cloth.
        val photoRow = BooleanArray(ui.height)
        val scratch = IntArray(ui.width)
        for (y in 0 until ui.height) {
            val row = y * ui.width
            for (x in 0 until ui.width) {
                val p = ui.pixels[row + x]
                scratch[x] = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
            }
            scratch.sort()
            photoRow[y] = scratch[ui.width / 2] > 70
        }
        var bestTop = -1
        var bestBot = -1
        var run = -1
        for (y in 0..ui.height) {
            val on = y < ui.height && photoRow[y]
            if (on && run < 0) run = y
            if (!on && run >= 0) {
                if (y - run > bestBot - bestTop) {
                    bestTop = run
                    bestBot = y - 1
                }
                run = -1
            }
        }
        if (bestTop < 0 || bestBot - bestTop < 200) return null
        var left = ui.width
        var right = 0
        for (y in bestTop..bestBot) {
            val row = y * ui.width
            for (x in 0 until ui.width) {
                val p = ui.pixels[row + x]
                val luma = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                if (luma > 40) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (right - left < 200) return null
        val crop = ImageOps.crop(ui, left, bestTop, right - left + 1, bestBot - bestTop + 1)
        val pixels = crop.pixels.clone()
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val stroke = r > 180 && g > 140 && b < 120 && r + g > b + 160
            if (stroke) pixels[i] = 0xFF6E6A64.toInt()
        }
        return RgbImage(crop.width, crop.height, pixels)
    }

    @Test
    fun phoneScreenshotsAreUiPhotosNotFullSensorStills() {
        val names = listOf(
            "preview-dice-seen.jpg",
            "preview-holding.jpg",
            "unread-two.jpg",
            "unread-three.jpg"
        )
        for (name in names) {
            val stream = javaClass.classLoader.getResourceAsStream("ui/$name")
                ?: error("missing ui/$name")
            val image = decodeJpeg(stream)
            val det = ColoredDiceReader.read(image)
            val boxes = det.dice.joinToString(" ") { "${it.w}x${it.h}@${it.x},${it.y}=${it.count}" }
            println(
                "ui $name ${image.width}x${image.height} dice=${det.dice.size} ok=${det.ok} " +
                    "reason=${det.reason} faces=${det.dice.joinToString(",") { it.count.toString() }} boxes=$boxes"
            )
            println("  sizes ${ColoredDiceReader.lastSizeLog}")
        }
    }

    private data class PhotoLabel(val face: Int, val dice: Int)

    private fun loadLabel(name: String): PhotoLabel {
        val text = javaClass.classLoader.getResourceAsStream("real/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("missing real/$name")
        var face = -1
        var dice = -1
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || !line.contains('=')) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            when (key) {
                "face" -> face = value.toInt()
                "dice" -> dice = value.toInt()
            }
        }
        check(face in 1..6) { "$name face=$face" }
        check(dice > 0) { "$name dice=$dice" }
        return PhotoLabel(face, dice)
    }

    private fun labeledPhotoDir(): java.io.File {
        val url = javaClass.classLoader.getResource("real/5b225ec207de1be1e8fd2427919642fb0c7bc4b8630598af0a8f92a7eeffcce7.label")
            ?: error("missing labeled photo")
        return java.io.File(url.toURI()).parentFile
    }

    private fun load(name: String): RgbImage {
        val stream = javaClass.classLoader.getResourceAsStream("real/$name")
            ?: error("missing test photo real/$name")
        return decodeJpeg(stream)
    }

    /** Host tests have ImageIO; the Android compile stubs do not. */
    private fun writePng(image: RgbImage, file: java.io.File) {
        val biClass = Class.forName("java.awt.image.BufferedImage")
        val type = biClass.getField("TYPE_INT_ARGB").get(null) as Int
        val bi = biClass.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).newInstance(image.width, image.height, type)
        biClass.getMethod(
            "setRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).invoke(bi, 0, 0, image.width, image.height, image.pixels, 0, image.width)
        val rendered = Class.forName("java.awt.image.RenderedImage")
        val wrote = Class.forName("javax.imageio.ImageIO")
            .getMethod("write", rendered, String::class.java, java.io.File::class.java)
            .invoke(null, bi, "png", file) as Boolean
        check(wrote) { "could not write ${file.absolutePath}" }
    }

    /**
     * Host unit tests have javax.imageio; the Android compile stubs do not, so load it by name.
     * ImageIO ignores EXIF. The phone rotates via ExifInterface, and every table photo here
     * is stored sideways, so the test rotates the same way before counting.
     */
    private fun decodeJpeg(stream: InputStream): RgbImage {
        val bytes = stream.readBytes()
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val buffered = imageIo.getMethod("read", InputStream::class.java).invoke(null, java.io.ByteArrayInputStream(bytes))
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
        return when (jpegOrientation(bytes)) {
            6 -> rotate90(w, h, pixels, clockwise = true)
            8 -> rotate90(w, h, pixels, clockwise = false)
            3 -> rotate180(w, h, pixels)
            else -> RgbImage(w, h, pixels)
        }
    }

    /** EXIF orientation tag, or 1 when the file has none. */
    private fun jpegOrientation(bytes: ByteArray): Int {
        var i = 2
        while (i + 4 < bytes.size && bytes[i] == 0xFF.toByte()) {
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0xD9) break
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > bytes.size) break
            if (marker == 0xE1 && i + 10 < bytes.size &&
                bytes[i + 4] == 'E'.code.toByte() && bytes[i + 5] == 'x'.code.toByte()
            ) {
                return exifOrientation(bytes, i + 10, i + 2 + len)
            }
            if (marker == 0xDA) break
            i += 2 + len
        }
        return 1
    }

    private fun exifOrientation(bytes: ByteArray, tiff: Int, end: Int): Int {
        if (tiff + 8 > end) return 1
        val le = bytes[tiff] == 'I'.code.toByte()
        fun u16(at: Int): Int {
            val a = bytes[at].toInt() and 0xFF
            val b = bytes[at + 1].toInt() and 0xFF
            return if (le) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(at: Int): Int {
            val a = bytes[at].toInt() and 0xFF
            val b = bytes[at + 1].toInt() and 0xFF
            val c = bytes[at + 2].toInt() and 0xFF
            val d = bytes[at + 3].toInt() and 0xFF
            return if (le) a or (b shl 8) or (c shl 16) or (d shl 24) else (a shl 24) or (b shl 16) or (c shl 8) or d
        }
        val ifd = tiff + u32(tiff + 4)
        if (ifd + 2 > end) return 1
        val n = u16(ifd)
        for (k in 0 until n) {
            val e = ifd + 2 + k * 12
            if (e + 12 > end) return 1
            if (u16(e) == 0x0112) return u16(e + 8)
        }
        return 1
    }

    private fun rotate90(w: Int, h: Int, pixels: IntArray, clockwise: Boolean): RgbImage {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = if (clockwise) h - 1 - y else y
                val ny = if (clockwise) x else w - 1 - x
                out[ny * h + nx] = pixels[y * w + x]
            }
        }
        return RgbImage(h, w, out)
    }

    private fun rotate180(w: Int, h: Int, pixels: IntArray): RgbImage {
        val out = IntArray(w * h)
        for (i in pixels.indices) out[pixels.size - 1 - i] = pixels[i]
        return RgbImage(w, h, out)
    }
}
