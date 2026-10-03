package com.srrtracker.detect

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the dice on a table by looking for blobs that are not the felt color.
 * Used to crop a high-res photo down to the dice before pip counting, so a die
 * that is only 40px in a 1080p frame is not shrunk away.
 */
object DiceLocator {
    data class Found(val rect: IntRect, val dieSide: Int)

    fun locate(image: RgbImage): Found? {
        val maxDim = 640
        val long = max(image.width, image.height)
        val scaleDown = if (long > maxDim) long.toFloat() / maxDim else 1f
        val ww = max(1, (image.width / scaleDown).toInt())
        val hh = max(1, (image.height / scaleDown).toInt())
        val small = if (ww == image.width && hh == image.height) image else ImageOps.scale(image, ww, hh)
        val felt = borderColor(small)
        val mask = BooleanArray(ww * hh)
        var fg = 0
        for (i in mask.indices) {
            val dr = small.red(i) - felt[0]
            val dg = small.green(i) - felt[1]
            val db = small.blue(i) - felt[2]
            val d = dr * dr + dg * dg + db * db
            if (d > 46 * 46) {
                mask[i] = true
                fg++
            }
        }
        if (fg < 30) return null

        val fullPerWork = image.width.toFloat() / ww
        val minSideWork = 14f / fullPerWork
        val maxSideWork = 460f / fullPerWork
        val minArea = max(24, (0.40 * minSideWork * minSideWork).toInt())
        val maxArea = max(minArea + 1, (1.4 * maxSideWork * maxSideWork).toInt())

        val comps = components(mask, ww, hh, minArea, maxArea)
        if (comps.isEmpty()) return null
        val chosen = pick(comps) ?: return null
        val sideWork = chosen.dieSideWork
        val rectWork = chosen.rect
        val x = (rectWork.x * image.width.toFloat() / ww).toInt()
        val y = (rectWork.y * image.height.toFloat() / hh).toInt()
        val rw = max(1, (rectWork.w * image.width.toFloat() / ww).toInt())
        val rh = max(1, (rectWork.h * image.height.toFloat() / hh).toInt())
        val sideFull = max(12, (sideWork * fullPerWork).toInt())
        val padded = ImageOps.pad(IntRect(x, y, rw, rh), image.width, image.height, sideFull)
        return Found(padded, sideFull)
    }

    private class Comp(val rect: IntRect, val area: Int, val cx: Double, val cy: Double)

    private class Choice(val rect: IntRect, val dieSideWork: Double)

    private fun pick(comps: List<Comp>): Choice? {
        var best: Pair<Comp, Comp>? = null
        var bestScore = Double.MAX_VALUE
        for (i in comps.indices) {
            for (j in i + 1 until comps.size) {
                val a = comps[i]
                val b = comps[j]
                val areaRatio = max(a.area, b.area).toDouble() / min(a.area, b.area)
                if (areaRatio > 2.4) continue
                val size = max(sqrt(a.area.toDouble()), sqrt(b.area.toDouble()))
                val dist = hypot(a.cx - b.cx, a.cy - b.cy)
                if (dist < size * 0.3 || dist > size * 6.5) continue
                val score = dist / size + areaRatio
                if (score < bestScore) {
                    bestScore = score
                    best = a to b
                }
            }
        }
        val pair = best
        if (pair != null) {
            val (a, b) = pair
            val x = min(a.rect.x, b.rect.x)
            val y = min(a.rect.y, b.rect.y)
            val r = max(a.rect.x + a.rect.w, b.rect.x + b.rect.w)
            val bot = max(a.rect.y + a.rect.h, b.rect.y + b.rect.h)
            val side = (sqrt(a.area.toDouble()) + sqrt(b.area.toDouble())) / 2.0 / 0.82
            return Choice(IntRect(x, y, r - x, bot - y), side)
        }
        val c = comps.maxBy { it.area }
        val side = sqrt(c.area.toDouble()) / 0.82
        val grow = (side * 1.6).toInt()
        return Choice(
            IntRect(c.rect.x - grow, c.rect.y - grow, c.rect.w + grow * 2, c.rect.h + grow * 2),
            side
        )
    }

    private fun components(mask: BooleanArray, w: Int, h: Int, minArea: Int, maxArea: Int): List<Comp> {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val out = ArrayList<Comp>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var sx = 0L
            var sy = 0L
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            while (sp > 0) {
                val q = stack[--sp]
                val x = q % w
                val y = q / w
                area++
                sx += x
                sy += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && mask[q - 1] && !seen[q - 1]) { seen[q - 1] = true; stack[sp++] = q - 1 }
                if (x < w - 1 && mask[q + 1] && !seen[q + 1]) { seen[q + 1] = true; stack[sp++] = q + 1 }
                if (y > 0 && mask[q - w] && !seen[q - w]) { seen[q - w] = true; stack[sp++] = q - w }
                if (y < h - 1 && mask[q + w] && !seen[q + w]) { seen[q + w] = true; stack[sp++] = q + w }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (area < minArea || area > maxArea) continue
            val aspect = bw.toDouble() / bh
            if (aspect < 0.35 || aspect > 2.9) continue
            val fill = area.toDouble() / (bw * bh)
            if (fill < 0.28) continue
            out.add(Comp(IntRect(minX, minY, bw, bh), area, sx.toDouble() / area, sy.toDouble() / area))
        }
        out.sortByDescending { it.area }
        return if (out.size > 8) out.subList(0, 8) else out
    }

    private fun borderColor(img: RgbImage): IntArray {
        val band = max(2, min(img.width, img.height) / 28)
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        val step = 3
        var y = 0
        while (y < img.height) {
            val edgeY = y < band || y >= img.height - band
            var x = 0
            while (x < img.width) {
                if (edgeY || x < band || x >= img.width - band) {
                    val i = y * img.width + x
                    rs.add(img.red(i))
                    gs.add(img.green(i))
                    bs.add(img.blue(i))
                }
                x += step
            }
            y += step
        }
        return intArrayOf(median(rs), median(gs), median(bs))
    }

    private fun median(v: List<Int>): Int {
        if (v.isEmpty()) return 128
        val s = v.sorted()
        return s[s.size / 2]
    }
}
