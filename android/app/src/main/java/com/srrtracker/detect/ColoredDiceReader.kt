package com.srrtracker.detect

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds small translucent purple and amber dice with white pips on a grey cloth.
 * Teal foam, grey wrinkles, and nearby clutter (boxes, bottles, tools) are the
 * wrong hue or the wrong shape, so they are not dice. More than two dice is
 * reported as a count instead of being forced into a pair.
 *
 * Only pixels inside [roi] are looked at. A null roi is the whole photo.
 */
object ColoredDiceReader {
    fun read(image: RgbImage, roi: NormRect? = null): DiceDetector.Detection {
        val t0 = System.nanoTime()
        val bounds = pixelRoi(image, roi)
        val work = if (bounds.x == 0 && bounds.y == 0 && bounds.w == image.width && bounds.h == image.height) {
            image
        } else {
            ImageOps.crop(image, bounds.x, bounds.y, bounds.w, bounds.h)
        }
        val located = locate(work).map { box ->
            box.copy(x = box.x + bounds.x, y = box.y + bounds.y)
        }
        val reads = located.map { countWhitePips(image, it) }
        val marks = reads.map { it.mark }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (located.size != 2) {
            return DiceDetector.Detection(
                ok = false,
                total = null,
                counts = null,
                confidence = "none",
                cost = null,
                margin = null,
                pips = emptyList(),
                reason = foundReason(located.size),
                ms = ms,
                dice = marks
            )
        }
        val bad = reads.indexOfFirst { it.count !in 1..6 }
        if (bad >= 0) {
            return DiceDetector.Detection(
                ok = false,
                total = null,
                counts = reads.map { it.count },
                confidence = "none",
                cost = null,
                margin = null,
                pips = emptyList(),
                reason = "pip count ${reads[bad].count} invalid",
                ms = ms,
                dice = marks
            )
        }
        val order = if (reads[0].mark.x <= reads[1].mark.x) intArrayOf(0, 1) else intArrayOf(1, 0)
        val c0 = reads[order[0]].count
        val c1 = reads[order[1]].count
        val guessed = listOf(c0, c1)
        val trusted = c0 in 1..6 && c1 in 1..6 && reads.all { it.clear }
        val pips = ArrayList<DiceDetector.PipMark>()
        if (trusted) {
            for (di in 0..1) {
                for (p in reads[order[di]].pips) pips.add(p.copy(die = di))
            }
        }
        // These translucent faces still produce extra bright spots, so a pair is
        // not saved on its own. The guess is kept for the check screen.
        return DiceDetector.Detection(
            ok = false,
            total = null,
            counts = if (c0 in 1..6 && c1 in 1..6) guessed else null,
            confidence = "none",
            cost = null,
            margin = null,
            pips = pips,
            reason = when {
                c0 !in 1..6 || c1 !in 1..6 -> "pip count ${if (c0 !in 1..6) c0 else c1} invalid"
                else -> "pips unclear"
            },
            ms = ms,
            dice = listOf(marks[order[0]], marks[order[1]])
        )
    }

    private fun foundReason(n: Int): String = if (n == 1) "found 1 die" else "found $n dice"

    private fun pixelRoi(image: RgbImage, roi: NormRect?): IntRect {
        if (roi == null) return IntRect(0, 0, image.width, image.height)
        val x = (roi.left * image.width).toInt().coerceIn(0, image.width - 1)
        val y = (roi.top * image.height).toInt().coerceIn(0, image.height - 1)
        val r = (roi.right * image.width).toInt().coerceIn(x + 1, image.width)
        val b = (roi.bottom * image.height).toInt().coerceIn(y + 1, image.height)
        return IntRect(x, y, r - x, b - y)
    }

    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int, val area: Int, val kind: Char) {
        fun copy(x: Int = this.x, y: Int = this.y, w: Int = this.w, h: Int = this.h) = Box(x, y, w, h, area, kind)
    }

    private class PipRead(
        val count: Int,
        val clear: Boolean,
        val mark: DiceDetector.DieMark,
        val pips: List<DiceDetector.PipMark>
    )

    private fun locate(image: RgbImage): List<Box> {
        val longSide = max(image.width, image.height)
        val scale = if (longSide > 1600) longSide / 1600f else 1f
        val sw = max(1, (image.width / scale).roundToInt())
        val sh = max(1, (image.height / scale).roundToInt())
        val small = if (sw == image.width && sh == image.height) image else ImageOps.scale(image, sw, sh)
        val purple = BooleanArray(sw * sh)
        val yellow = BooleanArray(sw * sh)
        for (i in purple.indices) {
            val r = small.red(i)
            val g = small.green(i)
            val b = small.blue(i)
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val chroma = mx - mn
            val pur = min(r, b) - g
            val yel = min(r, g) - b
            if (pur > 14 && mx in 50..239 && chroma > 12) purple[i] = true
            else if (yel > 48 && mx > 90 && b < 165 && chroma > 40 && g > 80) yellow[i] = true
        }
        val boxes = ArrayList<Box>()
        boxes += components(close(purple, sw, sh), sw, sh, 'P', scale, image.width, image.height)
        boxes += components(close(yellow, sw, sh), sw, sh, 'Y', scale, image.width, image.height)
        return dropSizeOutliers(mergeSameColor(boxes))
    }

    private fun dropSizeOutliers(boxes: List<Box>): List<Box> {
        if (boxes.size < 3) return boxes
        val sides = boxes.map { max(it.w, it.h) }.sorted()
        val median = sides[sides.size / 2].toFloat()
        return boxes.filter {
            val s = max(it.w, it.h).toFloat()
            s >= median * 0.62f && s <= median * 1.65f
        }
    }

    private fun components(
        mask: BooleanArray,
        w: Int,
        h: Int,
        kind: Char,
        scale: Float,
        fullW: Int,
        fullH: Int
    ): List<Box> {
        val minSide = (32f / scale).toInt().coerceIn(10, 48)
        val maxSide = (200f / scale).toInt().coerceIn(minSide + 8, 480)
        val minArea = (minSide * minSide * 0.35f).toInt().coerceAtLeast(40)
        val maxArea = maxSide * maxSide
        val seen = BooleanArray(mask.size)
        val out = ArrayList<Box>()
        val stack = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                area++
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (x > 0) push(mask, seen, stack, sp, i - 1).also { sp = it }
                if (x + 1 < w) push(mask, seen, stack, sp, i + 1).also { sp = it }
                if (y > 0) push(mask, seen, stack, sp, i - w).also { sp = it }
                if (y + 1 < h) push(mask, seen, stack, sp, i + w).also { sp = it }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) continue
            if (area !in minArea..maxArea) continue
            val aspect = bw.toFloat() / bh
            val fill = area.toFloat() / (bw * bh)
            if (aspect !in 0.55f..1.80f || fill < 0.32f) continue
            val x = (minX * scale).roundToInt().coerceIn(0, fullW - 1)
            val y = (minY * scale).roundToInt().coerceIn(0, fullH - 1)
            val r = ((maxX + 1) * scale).roundToInt().coerceIn(x + 1, fullW)
            val b = ((maxY + 1) * scale).roundToInt().coerceIn(y + 1, fullH)
            out += Box(x, y, r - x, b - y, area, kind)
        }
        return out
    }

    private fun push(mask: BooleanArray, seen: BooleanArray, stack: IntArray, sp: Int, i: Int): Int {
        if (seen[i] || !mask[i]) return sp
        seen[i] = true
        stack[sp] = i
        return sp + 1
    }

    /** Touching fragments of one die become one square. Different colors stay apart. */
    private fun mergeSameColor(boxes: List<Box>): List<Box> {
        val used = BooleanArray(boxes.size)
        val out = ArrayList<Box>()
        for (i in boxes.indices) {
            if (used[i]) continue
            var x0 = boxes[i].x
            var y0 = boxes[i].y
            var x1 = boxes[i].x + boxes[i].w
            var y1 = boxes[i].y + boxes[i].h
            val kind = boxes[i].kind
            var area = boxes[i].area
            used[i] = true
            var grew = true
            while (grew) {
                grew = false
                for (j in boxes.indices) {
                    if (used[j] || boxes[j].kind != kind) continue
                    val c = boxes[j]
                    val gap = max(c.w, c.h) / 8
                    val overlap = c.x < x1 + gap && c.x + c.w > x0 - gap && c.y < y1 + gap && c.y + c.h > y0 - gap
                    if (!overlap) continue
                    val nx0 = min(x0, c.x)
                    val ny0 = min(y0, c.y)
                    val nx1 = max(x1, c.x + c.w)
                    val ny1 = max(y1, c.y + c.h)
                    val aspect = (nx1 - nx0).toFloat() / max(1, ny1 - ny0)
                    if (aspect !in 0.60f..1.70f) continue
                    x0 = nx0
                    y0 = ny0
                    x1 = nx1
                    y1 = ny1
                    area += c.area
                    used[j] = true
                    grew = true
                }
            }
            val w = x1 - x0
            val h = y1 - y0
            val aspect = w.toFloat() / max(1, h)
            if (aspect in 0.60f..1.70f) out += Box(x0, y0, w, h, area, kind)
        }
        return out
    }

    private fun close(mask: BooleanArray, w: Int, h: Int): BooleanArray = erode(dilate(mask, w, h), w, h)

    private fun dilate(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (!mask[row + x]) continue
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until h) continue
                    val r2 = yy * w
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx in 0 until w) out[r2 + xx] = true
                    }
                }
            }
        }
        return out
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                var ok = true
                for (dy in -1..1) {
                    val r2 = (y + dy) * w
                    for (dx in -1..1) {
                        if (!mask[r2 + x + dx]) ok = false
                    }
                }
                out[row + x] = ok
            }
        }
        return out
    }

    /**
     * White pips are bright, low-chroma spots. A count is trusted only when
     * those spots are clearly stronger than the next brightest wrinkles.
     */
    private fun countWhitePips(image: RgbImage, die: Box): PipRead {
        val crop = ImageOps.crop(image, die.x, die.y, die.w, die.h)
        val w = crop.width
        val h = crop.height
        val n = w * h
        val white = FloatArray(n)
        val lum = FloatArray(n)
        for (i in 0 until n) {
            val r = crop.red(i).toFloat()
            val g = crop.green(i).toFloat()
            val b = crop.blue(i).toFloat()
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            lum[i] = 0.30f * r + 0.59f * g + 0.11f * b
            white[i] = lum[i] - 0.75f * (mx - mn)
        }
        val side = min(w, h).coerceAtLeast(8)
        val win = max(4, (side * 0.18f).toInt())
        val margin = max(2, (side * 0.08f).toInt())
        val pipR = max(2, (side * 0.07f).toInt())
        val peaks = ArrayList<Peak>()
        for (y in margin until h - margin) {
            for (x in margin until w - margin) {
                val v = white[y * w + x]
                if (!isLocalMax(white, w, h, x, y, win, v)) continue
                val score = contrast(lum, w, h, x, y, pipR)
                if (score > 8f) peaks += Peak(score, x, y)
            }
        }
        peaks.sortByDescending { it.score }
        val top = peaks.firstOrNull()?.score ?: 0f
        val thr = max(16f, top * 0.40f)
        val kept = peaks.filter { it.score >= thr }.take(8)
        val count = kept.size
        val strongest = kept.firstOrNull()?.score ?: 0f
        val weakest = kept.lastOrNull()?.score ?: 0f
        val next = peaks.getOrNull(kept.size)?.score ?: 0f
        val clear = count in 1..6 &&
            weakest >= strongest * 0.62f &&
            next <= weakest * 0.50f
        val pips = kept.map { p ->
            DiceDetector.PipMark(
                x = (die.x + p.x + 0.5) / image.width,
                y = (die.y + p.y + 0.5) / image.height,
                r = (side * 0.08) / max(image.width, image.height),
                die = 0
            )
        }
        return PipRead(
            count = count,
            clear = clear && count in 1..6,
            mark = DiceDetector.DieMark(die.x, die.y, die.w, die.h, count),
            pips = pips
        )
    }

    private class Peak(val score: Float, val x: Int, val y: Int)

    private fun isLocalMax(src: FloatArray, w: Int, h: Int, x: Int, y: Int, rad: Int, v: Float): Boolean {
        val y0 = max(0, y - rad)
        val y1 = min(h - 1, y + rad)
        val x0 = max(0, x - rad)
        val x1 = min(w - 1, x + rad)
        for (yy in y0..y1) {
            val row = yy * w
            for (xx in x0..x1) {
                val other = src[row + xx]
                if (other > v) return false
                if (other == v && (yy < y || (yy == y && xx < x))) return false
            }
        }
        return true
    }

    private fun contrast(lum: FloatArray, w: Int, h: Int, cx: Int, cy: Int, rad: Int): Float {
        var disk = 0.0
        var dn = 0
        var ring = 0.0
        var rn = 0
        val r2 = rad * rad
        val outer = (rad * 1.7f).toInt().coerceAtLeast(rad + 1)
        val o2 = outer * outer
        val y0 = max(0, cy - outer)
        val y1 = min(h - 1, cy + outer)
        val x0 = max(0, cx - outer)
        val x1 = min(w - 1, cx + outer)
        for (y in y0..y1) {
            val row = y * w
            val dy = y - cy
            for (x in x0..x1) {
                val d = (x - cx) * (x - cx) + dy * dy
                val v = lum[row + x].toDouble()
                if (d <= r2) {
                    disk += v
                    dn++
                } else if (d <= o2) {
                    ring += v
                    rn++
                }
            }
        }
        if (dn == 0 || rn == 0) return 0f
        return (disk / dn - ring / rn).toFloat()
    }
}
