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
        val label: String
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
        val feltR = 24
        val feltG = 92
        val feltB = 58
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val gain = 1.0 + if (spec.glare) 0.22 * (x.toDouble() / width - 0.15) else 0.03 * (x.toDouble() / width - 0.5)
                val n = rng.nextInt(7) - 3
                val r = ((feltR + n) * gain).toInt().coerceIn(0, 255)
                val g = ((feltG + n) * gain).toInt().coerceIn(0, 255)
                val b = ((feltB + n) * gain).toInt().coerceIn(0, 255)
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
        val body = if (spec.red) rgb(176, 32, 38) else rgb(242, 242, 236)
        val pip = if (spec.red) rgb(248, 246, 242) else rgb(22, 22, 24)
        drawDie(pixels, width, height, c1x, c1y, spec.side, spec.face1, spec.rot1, body, pip)
        drawDie(pixels, width, height, c2x, c2y, spec.side, spec.face2, spec.rot2, body, pip)
        return Scene(RgbImage(width, height, pixels), spec, c1x to c1y, c2x to c2y)
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
