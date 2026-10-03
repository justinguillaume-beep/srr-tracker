package com.srrtracker.detect

/** Shared frame geometry so the on-screen box and the motion gate describe the same spot. */
object FrameTarget {
    const val LEFT = 0.15f
    const val TOP = 0.18f
    const val RIGHT = 0.85f
    const val BOTTOM = 0.82f
}

data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun area(): Float = width * height
}

data class IntRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < x + w && py < y + h
}

/** ARGB pixels, row-major. No Android dependency so unit tests can build images. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height)
    }

    fun red(i: Int): Int = (pixels[i] shr 16) and 0xFF
    fun green(i: Int): Int = (pixels[i] shr 8) and 0xFF
    fun blue(i: Int): Int = pixels[i] and 0xFF
}

object ImageOps {
    fun crop(src: RgbImage, x: Int, y: Int, w: Int, h: Int): RgbImage {
        val x0 = x.coerceIn(0, src.width - 1)
        val y0 = y.coerceIn(0, src.height - 1)
        val x1 = (x + w).coerceIn(x0 + 1, src.width)
        val y1 = (y + h).coerceIn(y0 + 1, src.height)
        val cw = x1 - x0
        val ch = y1 - y0
        val out = IntArray(cw * ch)
        for (row in 0 until ch) {
            System.arraycopy(src.pixels, (y0 + row) * src.width + x0, out, row * cw, cw)
        }
        return RgbImage(cw, ch, out)
    }

    fun scale(src: RgbImage, nw: Int, nh: Int): RgbImage {
        if (nw == src.width && nh == src.height) return src
        val out = IntArray(nw * nh)
        val sw = src.width
        val sh = src.height
        for (y in 0 until nh) {
            var fy = (y + 0.5f) * sh / nh - 0.5f
            if (fy < 0f) fy = 0f
            val y0 = kotlin.math.floor(fy.toDouble()).toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceAtMost(sh - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            val row = y * nw
            for (x in 0 until nw) {
                var fx = (x + 0.5f) * sw / nw - 0.5f
                if (fx < 0f) fx = 0f
                val x0 = kotlin.math.floor(fx.toDouble()).toInt().coerceIn(0, sw - 1)
                val x1 = (x0 + 1).coerceAtMost(sw - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val c00 = src.pixels[y0 * sw + x0]
                val c10 = src.pixels[y0 * sw + x1]
                val c01 = src.pixels[y1 * sw + x0]
                val c11 = src.pixels[y1 * sw + x1]
                out[row + x] = lerpPixel(c00, c10, c01, c11, tx, ty)
            }
        }
        return RgbImage(nw, nh, out)
    }

    private fun lerpChannel(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun lerpPixel(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Int {
        fun ch(shift: Int): Int {
            val a = (c00 shr shift) and 0xFF
            val b = (c10 shr shift) and 0xFF
            val c = (c01 shr shift) and 0xFF
            val d = (c11 shr shift) and 0xFF
            val top = lerpChannel(a, b, tx)
            val bot = lerpChannel(c, d, tx)
            return lerpChannel(top, bot, ty)
        }
        val r = ch(16)
        val g = ch(8)
        val b = ch(0)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun pad(rect: IntRect, imageW: Int, imageH: Int, dieSide: Int): IntRect {
        val pad = maxOf(dieSide / 2, maxOf(rect.w, rect.h) / 3, 12)
        val x = (rect.x - pad).coerceAtLeast(0)
        val y = (rect.y - pad).coerceAtLeast(0)
        val right = (rect.x + rect.w + pad).coerceAtMost(imageW)
        val bottom = (rect.y + rect.h + pad).coerceAtMost(imageH)
        return IntRect(x, y, (right - x).coerceAtLeast(1), (bottom - y).coerceAtLeast(1))
    }
}
