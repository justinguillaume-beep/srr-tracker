package com.srrtracker

import com.srrtracker.detect.PhotoDiceReader
import com.srrtracker.detect.RgbImage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Photo-like scenes: noise, perspective, shadows, glare, wood, rounded dice,
 * and dice that are not the same color. The clean drawings are included so a
 * reader that only works on flat felt still fails here.
 */
class PhotoDiceReaderTest {
    @Test
    fun realisticPhotosAreMostlyRight() {
        val cases = listOf(
            photo(80, 4, 3, "white", "white", false, 1.0, 0.0, 0, "clean white"),
            photo(70, 6, 1, "red", "red", true, 1.0, 0.0, 8, "red glare noise"),
            photo(90, 2, 5, "white", "red", false, 0.82, 0.08, 10, "mixed perspective"),
            photo(60, 5, 5, "ivory", "blue", true, 0.78, -0.06, 12, "ivory blue wood", wood = true),
            photo(50, 1, 6, "clear", "white", false, 0.9, 0.04, 14, "clear on felt"),
            photo(75, 3, 3, "black", "red", true, 0.85, 0.1, 9, "black red glare"),
            photo(65, 2, 2, "white", "ivory", false, 1.0, 0.0, 16, "wood grain", wood = true),
            photo(85, 6, 4, "blue", "white", false, 0.75, 0.12, 11, "squashed blue"),
            photo(55, 1, 1, "red", "ivory", true, 0.88, -0.05, 7, "small mixed"),
            photo(100, 4, 6, "white", "black", false, 0.8, 0.0, 6, "large shadow")
        )
        var ok = 0
        val lines = ArrayList<String>()
        for ((i, spec) in cases.withIndex()) {
            val scene = SyntheticTable.render(spec, width = 1280, height = 720, seed = 40 + i)
            val det = PhotoDiceReader.read(scene.image)
            val truth = spec.face1 + spec.face2
            val faces = det.ok && det.counts != null && (
                (det.counts[0] == spec.face1 && det.counts[1] == spec.face2) ||
                    (det.counts[0] == spec.face2 && det.counts[1] == spec.face1)
                )
            if (faces) ok++
            lines += "${spec.label}: ${det.counts?.joinToString("+") ?: "?"} ${det.confidence} ${det.reason ?: ""} ${if (faces) "OK" else "MISS"}"
        }
        val report = lines.joinToString("\n")
        println("REALISTIC\n$report\n$ok/${cases.size}")
        assertTrue(report, ok.toDouble() / cases.size >= 0.6)
    }

    @Test
    fun oneDieSaysFoundOneDie() {
        val spec = SyntheticTable.Spec(80, 4, 2, false, 0.0, 0.0, false, 30.0, 220, 180, "one")
        val scene = SyntheticTable.render(spec, width = 640, height = 480, seed = 1)
        val det = PhotoDiceReader.read(scene.image)
        println("ONE ${det.reason} dice=${det.dice.size}")
        assertTrue(det.reason ?: "no reason", det.reason?.contains("found 1 die") == true)
    }

    @Test
    fun tooManyPipsAreCalledInvalid() {
        val w = 480
        val h = 320
        val pixels = IntArray(w * h) { 0xFF1C5A38.toInt() }
        fun disk(cx: Int, cy: Int, r: Int, color: Int) {
            for (y in cy - r..cy + r) {
                if (y !in 0 until h) continue
                for (x in cx - r..cx + r) {
                    if (x !in 0 until w) continue
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= r * r) pixels[y * w + x] = color
                }
            }
        }
        disk(160, 150, 62, 0xFFF4F4F0.toInt())
        disk(340, 160, 62, 0xFFF4F4F0.toInt())
        disk(340, 160, 9, 0xFF141414.toInt())
        for (i in 0 until 8) {
            disk(124 + (i % 4) * 24, 126 + (i / 4) * 26, 7, 0xFF141414.toInt())
        }
        val det = PhotoDiceReader.read(RgbImage(w, h, pixels))
        println("PIPS ${det.counts} ${det.reason}")
        assertTrue(det.reason ?: "no reason", det.reason?.startsWith("pip count") == true && det.reason.contains("invalid"))
    }

    private fun photo(
        side: Int,
        a: Int,
        b: Int,
        c1: String,
        c2: String,
        glare: Boolean,
        squash: Double,
        shear: Double,
        noise: Int,
        label: String,
        wood: Boolean = false
    ) = SyntheticTable.Spec(
        side = side,
        face1 = a,
        face2 = b,
        red = false,
        rot1 = 12.0,
        rot2 = -18.0,
        glare = glare,
        gap = 0.45,
        ox = 280,
        oy = 220,
        label = label,
        wood = wood,
        color1 = c1,
        color2 = c2,
        squash = squash,
        shear = shear,
        photoNoise = noise
    )
}
