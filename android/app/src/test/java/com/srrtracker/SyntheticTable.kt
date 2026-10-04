package com.srrtracker

import com.srrtracker.detect.RgbImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic felt + two dice. Sides are in pixels, so 40 means a genuinely small die. */
object SyntheticTable {
    data class Spec(
        val side: Int,
        val face1: Int,
        val face2: Int,
        val red: Boolean,
        val rot1: Double,
        val rot2: Double,
        val glare: Boolean,
        val gap: Double,
        val ox: Int,
        val oy: Int,
        val label: String,
        val wood: Boolean = false,
        val lightTable: Boolean = false,
        val color1: String? = null,
        val color2: String? = null,
        val pip1: String? = null,
        val pip2: String? = null,
        val squash: Double = 1.0,
        val shear: Double = 0.0,
        val photoNoise: Int = 0
    )

    data class Scene(
        val image: RgbImage,
        val spec: Spec,
        val c1: Pair<Int, Int>,
        val c2: Pair<Int, Int>
    )

    private val pipLayouts = mapOf(
        1 to listOf(0 to 0),
        2 to listOf(-1 to -1, 1 to 1),
        3 to listOf(-1 to -1, 0 to 0, 1 to 1),
        4 to listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1),
        5 to listOf(-1 to -1, 1 to -1, 0 to 0, -1 to 1, 1 to 1),
        6 to listOf(-1 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 1 to 1)
    )

    fun render(spec: Spec, width: Int = 1280, height: Int = 720, seed: Int = 7): Scene {
        val pixels = IntArray(width * height)
        val rng = Random(seed)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val gain = 1.0 + if (spec.glare) 0.22 * (x.toDouble() / width - 0.15) else 0.03 * (x.toDouble() / width - 0.5)
                val n = rng.nextInt(7) - 3
                val base = when {
                    spec.lightTable -> intArrayOf(228 + n / 2, 222 + n / 2, 204 + n / 2)
                    spec.wood -> woodPixel(x, y, n)
                    else -> intArrayOf(24 + n, 92 + n, 58 + n)
                }
                val r = (base[0] * gain).toInt().coerceIn(0, 255)
                val g = (base[1] * gain).toInt().coerceIn(0, 255)
                val b = (base[2] * gain).toInt().coerceIn(0, 255)
                pixels[row + x] = rgb(r, g, b)
            }
        }
        repeat(18) {
            val cx = rng.nextInt(width)
            val cy = rng.nextInt(height)
            val rad = rng.nextInt(2, 6)
            blotch(pixels, width, height, cx, cy, rad, rgb(12, 46, 30))
        }
        val c1x = spec.ox
        val c1y = spec.oy
        val c2x = c1x + spec.side + (spec.side * spec.gap).toInt()
        val c2y = c1y + (spec.side * 0.12).toInt()
        val pair1 = dieColors(spec.color1, spec.red, spec.pip1)
        val pair2 = dieColors(spec.color2 ?: spec.color1, spec.red, spec.pip2 ?: spec.pip1)
        drawDie(pixels, width, height, c1x, c1y, spec.side, spec.face1, spec.rot1, pair1.first, pair1.second)
        drawDie(pixels, width, height, c2x, c2y, spec.side, spec.face2, spec.rot2, pair2.first, pair2.second)
        if (spec.glare) {
            blotch(pixels, width, height, c1x - spec.side / 5, c1y - spec.side / 5, maxOf(2, spec.side / 7), rgb(255, 255, 250))
        }
        var image = RgbImage(width, height, pixels)
        if (spec.squash != 1.0 || spec.shear != 0.0) image = warp(image, spec.squash, spec.shear)
        if (spec.photoNoise > 0) addNoise(image, spec.photoNoise, rng)
        return Scene(image, spec, c1x to c1y, c2x to c2y)
    }

    private fun dieColors(name: String?, red: Boolean, pipName: String?): Pair<Int, Int> {
        val key = name ?: if (red) "red" else "white"
        val body = when (key) {
            "red" -> rgb(186, 32, 40)
            "yellow" -> rgb(236, 196, 32)
            "blue" -> rgb(26, 58, 168)
            "ivory" -> rgb(226, 206, 160)
            "black" -> rgb(28, 28, 32)
            "clear" -> rgb(198, 214, 206)
            else -> rgb(242, 242, 236)
        }
        val pip = when (pipName ?: defaultPip(key)) {
            "white" -> rgb(248, 248, 244)
            else -> rgb(16, 16, 18)
        }
        return body to pip
    }

    private fun defaultPip(body: String): String = when (body) {
        "red", "blue", "black" -> "white"
        else -> "black"
    }

    private fun woodPixel(x: Int, y: Int, n: Int): IntArray {
        val stripe = ((kotlin.math.sin(y / 9.0) + kotlin.math.sin(x / 37.0)) * 14).toInt()
        return intArrayOf(148 + stripe + n, 104 + stripe / 2 + n, 62 + n)
    }

    private fun warp(src: RgbImage, squash: Double, shear: Double): RgbImage {
        val w = src.width
        val h = src.height
        val out = IntArray(w * h)
        val cy = h / 2.0
        for (y in 0 until h) {
            val sy = cy + (y - cy) / squash
            val y0 = kotlin.math.floor(sy).toInt()
            val y1 = y0 + 1
            val ty = (sy - y0).toFloat().coerceIn(0f, 1f)
            val shift = ((y - cy) * shear).toInt()
            for (x in 0 until w) {
                val sx = x - shift
                if (sx !in 0 until w || y0 !in 0 until h) {
                    out[y * w + x] = src.pixels[(y.coerceIn(0, h - 1)) * w + x.coerceIn(0, w - 1)]
                    continue
                }
                val a = src.pixels[y0 * w + sx]
                val b = if (y1 in 0 until h) src.pixels[y1 * w + sx] else a
                out[y * w + x] = mix(a, b, ty)
            }
        }
        return RgbImage(w, h, out)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun addNoise(image: RgbImage, amount: Int, rng: Random) {
        val px = image.pixels
        for (i in px.indices) {
            if (rng.nextInt(3) != 0) continue
            val n = rng.nextInt(-amount, amount + 1)
            val c = px[i]
            val r = (((c shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((c and 0xFF) + n).coerceIn(0, 255)
            px[i] = rgb(r, g, b)
        }
    }

    fun closeUp(face1: Int, face2: Int, red: Boolean = false): RgbImage {
        val scene = render(
            Spec(
                side = 150,
                face1 = face1,
                face2 = face2,
                red = red,
                rot1 = 12.0,
                rot2 = -20.0,
                glare = false,
                gap = 0.35,
                ox = 180,
                oy = 160,
                label = "close $face1+$face2"
            ),
            width = 640,
            height = 480,
            seed = 3
        )
        return scene.image
    }

    private fun drawDie(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        side: Int,
        face: Int,
        rotDeg: Double,
        body: Int,
        pip: Int
    ) {
        val half = side / 2.0
        val corner = side * 0.10
        val pipR = side * 0.090
        val rad = Math.toRadians(rotDeg)
        val c = cos(rad)
        val s = sin(rad)
        val reach = (side * 0.78).toInt()
        val shadow = rgb(8, 28, 18)
        for (y in (cy - reach)..(cy + reach)) {
            if (y !in 0 until height) continue
            for (x in (cx - reach + 3)..(cx + reach + 3)) {
                if (x !in 0 until width) continue
                val dx = x - (cx + side * 0.06)
                val dy = y - (cy + side * 0.08)
                if (dx * dx / (half * half) + dy * dy / (half * 0.9 * half * 0.9) <= 1.0) {
                    pixels[y * width + x] = shadow
                }
            }
        }
        val layout = pipLayouts.getValue(face)
        for (y in (cy - reach)..(cy + reach)) {
            if (y !in 0 until height) continue
            val row = y * width
            for (x in (cx - reach)..(cx + reach)) {
                if (x !in 0 until width) continue
                val dx = (x - cx).toDouble()
                val dy = (y - cy).toDouble()
                val lx = dx * c + dy * s
                val ly = -dx * s + dy * c
                val ax = abs(lx)
                val ay = abs(ly)
                if (ax > half || ay > half) continue
                val inner = half - corner
                if (ax > inner && ay > inner) {
                    val ex = ax - inner
                    val ey = ay - inner
                    if (ex * ex + ey * ey > corner * corner) continue
                }
                var color = body
                for ((fx, fy) in layout) {
                    val px = fx * side * 0.27
                    val py = fy * side * 0.27
                    val ddx = lx - px
                    val ddy = ly - py
                    if (ddx * ddx + ddy * ddy <= pipR * pipR) {
                        color = pip
                        break
                    }
                }
                pixels[row + x] = color
            }
        }
    }

    private fun blotch(pixels: IntArray, width: Int, height: Int, cx: Int, cy: Int, rad: Int, color: Int) {
        for (y in (cy - rad)..(cy + rad)) {
            if (y !in 0 until height) continue
            for (x in (cx - rad)..(cx + rad)) {
                if (x !in 0 until width) continue
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= rad * rad) pixels[y * width + x] = color
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
