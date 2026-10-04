package com.srrtracker.detect

import kotlin.math.max
import kotlin.math.roundToInt

/** Draws the dice boxes and pip circles onto a smaller copy of the photo. */
object DebugMarks {
    fun annotate(image: RgbImage, detection: DiceDetector.Detection): RgbImage {
        val long = max(image.width, image.height)
        val scale = if (long > 960) long.toFloat() / 960f else 1f
        val nw = max(1, (image.width / scale).roundToInt())
        val nh = max(1, (image.height / scale).roundToInt())
        val base = if (nw == image.width && nh == image.height) image else ImageOps.scale(image, nw, nh)
        val px = base.pixels.copyOf()
        val green = 0xFF3DDC84.toInt()
        val yellow = 0xFFFFD400.toInt()
        for (die in detection.dice) {
            val x0 = (die.x / scale).roundToInt()
            val y0 = (die.y / scale).roundToInt()
            val x1 = ((die.x + die.w) / scale).roundToInt()
            val y1 = ((die.y + die.h) / scale).roundToInt()
            strokeRect(px, nw, nh, x0, y0, x1, y1, green)
        }
        for (pip in detection.pips) {
            val cx = (pip.x * nw).roundToInt()
            val cy = (pip.y * nh).roundToInt()
            val r = max(3, (pip.r * max(nw, nh)).roundToInt())
            strokeCircle(px, nw, nh, cx, cy, r, yellow)
        }
        return RgbImage(nw, nh, px)
    }

    private fun strokeRect(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (t in 0..2) {
            hLine(px, w, h, x0 - t, x1 + t, y0 - t, color)
            hLine(px, w, h, x0 - t, x1 + t, y1 + t, color)
            vLine(px, w, h, x0 - t, y0 - t, y1 + t, color)
            vLine(px, w, h, x1 + t, y0 - t, y1 + t, color)
        }
    }

    private fun hLine(px: IntArray, w: Int, h: Int, x0: Int, x1: Int, y: Int, color: Int) {
        if (y !in 0 until h) return
        val a = x0.coerceIn(0, w - 1)
        val b = x1.coerceIn(0, w - 1)
        val row = y * w
        for (x in minOf(a, b)..maxOf(a, b)) px[row + x] = color
    }

    private fun vLine(px: IntArray, w: Int, h: Int, x: Int, y0: Int, y1: Int, color: Int) {
        if (x !in 0 until w) return
        val a = y0.coerceIn(0, h - 1)
        val b = y1.coerceIn(0, h - 1)
        for (y in minOf(a, b)..maxOf(a, b)) px[y * w + x] = color
    }

    private fun strokeCircle(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int, color: Int) {
        var x = r
        var y = 0
        var err = 1 - x
        while (x >= y) {
            plot(px, w, h, cx + x, cy + y, color)
            plot(px, w, h, cx + y, cy + x, color)
            plot(px, w, h, cx - y, cy + x, color)
            plot(px, w, h, cx - x, cy + y, color)
            plot(px, w, h, cx - x, cy - y, color)
            plot(px, w, h, cx - y, cy - x, color)
            plot(px, w, h, cx + y, cy - x, color)
            plot(px, w, h, cx + x, cy - y, color)
            y++
            if (err < 0) err += 2 * y + 1 else {
                x--
                err += 2 * (y - x) + 1
            }
        }
    }

    private fun plot(px: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int) {
        if (x in 0 until w && y in 0 until h) px[y * w + x] = color
    }
}
