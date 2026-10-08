package com.srrtracker.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds small translucent purple, amber, and red/pink dice with white pips on a
 * grey cloth. Teal foam, grey wrinkles, and nearby clutter (boxes, bottles, tools)
 * are the wrong hue or the wrong shape, so they are not dice. More than two dice
 * is reported as a count instead of being forced into a pair.
 *
 * Only pixels inside [roi] are looked at. A null roi is the whole photo.
 *
 * The color mask is a downscale (long side 1600). Each mask pixel maps back with
 * its own x and y scale (`full / small`), so a 3060×1826 still is not shifted by
 * a single long-side factor. The saved still is already the framing crop, so
 * these boxes are in that still's pixels — the center-crop offset is not added
 * again here.
 */
object ColoredDiceReader {
    /**
     * Per-die sizes from the last [read]: full-resolution crop, then the
     * upscaled face the pip counter actually used. Empty if nothing was found.
     */
    var lastSizeLog: String = ""

    /**
     * How many colored dice [locate] sees. Used to decide whether a frame is a
     * throw at all. Teal cloth and a grey table are not dice.
     */
    fun diceCount(image: RgbImage, roi: NormRect? = null): Int {
        val bounds = pixelRoi(image, roi)
        var work = if (bounds.x == 0 && bounds.y == 0 && bounds.w == image.width && bounds.h == image.height) {
            image
        } else {
            ImageOps.crop(image, bounds.x, bounds.y, bounds.w, bounds.h)
        }
        val longSide = max(work.width, work.height)
        // A very small crop is enlarged so a ~12px die is not a one-pixel speck.
        // A wide analysis frame is left at its own size: the small-die gate
        // accepts a die of about 15px, which is what the wide view shows.
        if (longSide < 420) {
            val factor = if (longSide < 220) 3 else 2
            work = ImageOps.scale(
                work,
                (work.width * factor).coerceAtLeast(1),
                (work.height * factor).coerceAtLeast(1)
            )
        }
        return locate(work, smallDice = true).size
    }

    fun read(image: RgbImage, roi: NormRect? = null): DiceDetector.Detection {
        lastSizeLog = ""
        val t0 = System.nanoTime()
        val bounds = pixelRoi(image, roi)
        val work = if (bounds.x == 0 && bounds.y == 0 && bounds.w == image.width && bounds.h == image.height) {
            image
        } else {
            ImageOps.crop(image, bounds.x, bounds.y, bounds.w, bounds.h)
        }
        val located = locate(work, smallDice = false).map { box ->
            box.copy(x = box.x + bounds.x, y = box.y + bounds.y)
        }.sortedWith(compareBy({ it.y }, { it.x }))
        val reads = located.map { countWhitePips(image, it) }
        lastSizeLog = reads.joinToString(" | ") { it.sizeLog }
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
        val margin = reads.minOf { it.margin }
        if (trusted) {
            return DiceDetector.Detection(
                ok = true,
                total = c0 + c1,
                counts = guessed,
                confidence = "high",
                cost = null,
                margin = margin.toDouble(),
                pips = pips,
                ms = ms,
                dice = listOf(marks[order[0]], marks[order[1]])
            )
        }
        return DiceDetector.Detection(
            ok = false,
            total = null,
            counts = if (c0 in 1..6 && c1 in 1..6) guessed else null,
            confidence = "none",
            cost = null,
            margin = margin.toDouble(),
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
        val margin: Float,
        val mark: DiceDetector.DieMark,
        val pips: List<DiceDetector.PipMark>,
        val sizeLog: String
    )

    private fun locate(image: RgbImage, smallDice: Boolean): List<Box> {
        val longSide = max(image.width, image.height)
        val scale = if (longSide > 1600) longSide / 1600f else 1f
        val sw = max(1, (image.width / scale).roundToInt())
        val sh = max(1, (image.height / scale).roundToInt())
        val small = if (sw == image.width && sh == image.height) image else ImageOps.scale(image, sw, sh)
        // Round(full/scale) can leave sw*scale != width. Map each axis by the
        // real small-image size so a die near the far edge is not shifted.
        val xScale = image.width.toFloat() / sw
        val yScale = image.height.toFloat() / sh
        val purple = BooleanArray(sw * sh)
        val yellow = BooleanArray(sw * sh)
        val yellowCore = BooleanArray(sw * sh)
        val red = BooleanArray(sw * sh)
        val redCore = BooleanArray(sw * sh)
        for (i in purple.indices) {
            val r = small.red(i)
            val g = small.green(i)
            val b = small.blue(i)
            val mx = max(r, max(g, b))
            val chroma = mx - min(r, min(g, b))
            val pur = min(r, b) - g
            val yel = min(r, g) - b
            val purCut = if (smallDice) 8 else 14
            if (pur > purCut && mx in 50..239 && chroma > if (smallDice) 8 else 12) purple[i] = true
            else if (yel > 48 && mx > 90 && b < 165 && chroma > 40 && g > 80) {
                // The cut stays at 48 so ivory (about 46) is not a die. Tan cloth
                // can still cross 48, so a real gold die also has to have a brighter core.
                yellow[i] = true
                if (yel > 60) yellowCore[i] = true
            } else if (redDie(r, g, b, chroma) || (smallDice && softRed(r, g, b))) {
                red[i] = true
                // Tan patches can cross the red cut after the JPEG is decoded.
                // A real red die still has a much stronger core. In the wide
                // view a translucent die is mixed with the cloth, so the core
                // is allowed to be a little weaker there.
                val coreCut = if (smallDice) 28 else 64
                if (r - max(g, b) > coreCut) redCore[i] = true
            }
        }
        val boxes = ArrayList<Box>()
        // Radius 1 keeps two nearby purple dice apart. A six has more pip holes
        // than a four, and those holes can leave the body in pieces too small to
        // keep. Radius 3 joins the pieces. That box is added only when half of it
        // was already purple, so a close around a speck is not a die, and only
        // when it is not already one of the tighter boxes.
        val purpleTight = components(close(purple, sw, sh, 1), sw, sh, 'P', scale, xScale, yScale, image.width, image.height, 0.32f, null, smallDice = smallDice)
        val purpleWide = components(close(purple, sw, sh, 3), sw, sh, 'P', scale, xScale, yScale, image.width, image.height, 0.32f, purple, 5, smallDice)
        boxes += purpleTight
        // Gold dice are translucent, so the yellow mask is full of pip holes.
        // A wider close joins those holes without lowering the yellow threshold.
        boxes += components(close(yellow, sw, sh, 3), sw, sh, 'Y', scale, xScale, yScale, image.width, image.height, 0.27f, yellowCore, smallDice = smallDice)
        // Red/pink dice are translucent too. Close radius 2 fills pip holes
        // without pulling in the warm grey cloth (that stays under the margin).
        boxes += components(close(red, sw, sh, 2), sw, sh, 'R', scale, xScale, yScale, image.width, image.height, 0.28f, redCore, smallDice = smallDice)
        boxes += purpleWide.filter { wide -> boxes.none { overlapsDie(it, wide) } }
        return dropSizeOutliers(dropContained(mergeSameColor(boxes)))
    }

    /**
     * A red or pink die has R clearly above both G and B. Amber stays on the
     * yellow test (margin 48, so ivory near 46 is not taken). Warm grey cloth
     * is only a few levels redder than green, which this margin rejects.
     */
    /**
     * Translucent red mixed with grey cloth in a wide view. Still redder than
     * the cloth, and not the warm-tan clutter the strict cut is there to drop.
     */
    private fun softRed(r: Int, g: Int, b: Int): Boolean {
        val margin = r - max(g, b)
        val chroma = max(r, max(g, b)) - min(r, min(g, b))
        return margin > 22 && chroma > 24 && r > 90 && r - g > 16 && r - b > 16 && g < 190 && b < 180
    }

    private fun redDie(r: Int, g: Int, b: Int, chroma: Int): Boolean {
        val margin = r - max(g, b)
        // Margin 40 keeps warm tan clutter (red ahead of green by ~25) off the
        // mask. Pink dice on the check-screen stills are well above this.
        return margin > 40 && chroma > 44 && r > 90 && r - g > 34 && r - b > 28 && g < 175 && b < 155
    }

    private fun dropSizeOutliers(boxes: List<Box>): List<Box> {
        if (boxes.size < 3) return boxes
        val sides = boxes.map { max(it.w, it.h) }.sorted()
        val median = sides[sides.size / 2].toFloat()
        return boxes.filter {
            val s = max(it.w, it.h).toFloat()
            s >= median * 0.58f && s <= median * 1.65f
        }
    }

    private fun components(
        mask: BooleanArray,
        w: Int,
        h: Int,
        kind: Char,
        scale: Float,
        xScale: Float,
        yScale: Float,
        fullW: Int,
        fullH: Int,
        minFill: Float,
        core: BooleanArray?,
        coreTenths: Int = 1,
        smallDice: Boolean = false
    ): List<Box> {
        // The full-resolution still is masked at 1600px, so 32px there is a
        // normal die. The wide preview is not scaled down that way, and a die
        // in that view is about 15px. The shutter uses the smaller gate.
        val minSide = if (smallDice) {
            (10f / scale).toInt().coerceIn(6, 24)
        } else {
            (32f / scale).toInt().coerceIn(10, 48)
        }
        val maxSide = (200f / scale).toInt().coerceIn(minSide + 8, 480)
        val minArea = (minSide * minSide * 0.28f).toInt().coerceAtLeast(if (smallDice) 8 else 40)
        val minCore = if (smallDice) 3 else 8
        val maxArea = maxSide * maxSide
        val seen = BooleanArray(mask.size)
        val out = ArrayList<Box>()
        val stack = IntArray(mask.size)
        val pix = IntArray(mask.size)
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
                pix[area++] = i
                val x = i % w
                val y = i / w
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
            // Two dice that touch become one blob wider than a single die.
            // Split that blob at its narrow waist. A blob we already accept is left whole.
            if ((bw > maxSide && bh in minSide..maxSide) || (bh > maxSide && bw in minSide..maxSide)) {
                for (span in splitTouching(pix, area, w, bw > maxSide, minSide, maxSide)) {
                    consider(out, span, kind, xScale, yScale, fullW, fullH, minSide, maxSide, minArea, maxArea, minFill, w, core, coreTenths, minCore)
                }
                continue
            }
            if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) continue
            if (area !in minArea..maxArea) continue
            val aspect = bw.toFloat() / bh
            val fill = area.toFloat() / (bw * bh)
            if (aspect !in 0.55f..1.80f || fill < minFill) continue
            if (!saturatedCore(core, pix, area, coreTenths, minCore)) continue
            out += toBox(minX, minY, maxX, maxY, area, kind, xScale, yScale, fullW, fullH)
        }
        return out
    }

    private class Span(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int)

    /** Cut a side-by-side or stacked pair at the narrowest column or row. */
    private fun splitTouching(pix: IntArray, n: Int, imgW: Int, horizontal: Boolean, minSide: Int, maxSide: Int): List<Span> {
        var minA = Int.MAX_VALUE
        var maxA = 0
        for (k in 0 until n) {
            val a = if (horizontal) pix[k] % imgW else pix[k] / imgW
            if (a < minA) minA = a
            if (a > maxA) maxA = a
        }
        val len = maxA - minA + 1
        if (len <= minSide * 2) return emptyList()
        val count = IntArray(len)
        for (k in 0 until n) {
            val a = if (horizontal) pix[k] % imgW else pix[k] / imgW
            count[a - minA]++
        }
        var peak = 0
        for (c in count) if (c > peak) peak = c
        // Both halves have to be a single die. A cut in the thin tail does not count.
        val lo = max(minSide, len - 1 - maxSide)
        val hi = min(len - minSide, maxSide)
        if (lo >= hi) return emptyList()
        var bestI = -1
        var bestV = Int.MAX_VALUE
        for (i in lo until hi) {
            if (count[i] < bestV) {
                bestV = count[i]
                bestI = i
            }
        }
        if (bestI < 0 || peak == 0 || bestV > peak * 0.45f) return emptyList()
        var leftPeak = 0
        var rightPeak = 0
        for (i in 0 until bestI) if (count[i] > leftPeak) leftPeak = count[i]
        for (i in bestI + 1 until len) if (count[i] > rightPeak) rightPeak = count[i]
        if (leftPeak < minSide || rightPeak < minSide) return emptyList()
        if (bestV > leftPeak * 0.55f || bestV > rightPeak * 0.55f) return emptyList()
        val cut = minA + bestI
        var lx0 = Int.MAX_VALUE
        var ly0 = Int.MAX_VALUE
        var lx1 = 0
        var ly1 = 0
        var la = 0
        var rx0 = Int.MAX_VALUE
        var ry0 = Int.MAX_VALUE
        var rx1 = 0
        var ry1 = 0
        var ra = 0
        for (k in 0 until n) {
            val i = pix[k]
            val x = i % imgW
            val y = i / imgW
            val a = if (horizontal) x else y
            if (a < cut) {
                la++
                if (x < lx0) lx0 = x
                if (y < ly0) ly0 = y
                if (x > lx1) lx1 = x
                if (y > ly1) ly1 = y
            } else if (a > cut) {
                ra++
                if (x < rx0) rx0 = x
                if (y < ry0) ry0 = y
                if (x > rx1) rx1 = x
                if (y > ry1) ry1 = y
            }
        }
        if (la == 0 || ra == 0) return emptyList()
        return listOf(Span(lx0, ly0, lx1, ly1, la), Span(rx0, ry0, rx1, ry1, ra))
    }

    private fun consider(
        out: MutableList<Box>,
        span: Span,
        kind: Char,
        xScale: Float,
        yScale: Float,
        fullW: Int,
        fullH: Int,
        minSide: Int,
        maxSide: Int,
        minArea: Int,
        maxArea: Int,
        minFill: Float,
        imageW: Int,
        core: BooleanArray?,
        coreTenths: Int,
        minCore: Int
    ) {
        val bw = span.x1 - span.x0 + 1
        val bh = span.y1 - span.y0 + 1
        if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) return
        if (span.area !in minArea..maxArea) return
        val aspect = bw.toFloat() / bh
        val fill = span.area.toFloat() / (bw * bh)
        if (aspect !in 0.55f..1.80f || fill < minFill) return
        if (core != null && !spanHasCore(core, imageW, span, coreTenths, minCore)) return
        out += toBox(span.x0, span.y0, span.x1, span.y1, span.area, kind, xScale, yScale, fullW, fullH)
    }

    private fun spanHasCore(core: BooleanArray, imageW: Int, span: Span, coreTenths: Int, minCore: Int): Boolean {
        var c = 0
        for (y in span.y0..span.y1) {
            val row = y * imageW
            for (x in span.x0..span.x1) if (core[row + x]) c++
        }
        return c >= minCore && c * 10 >= span.area * coreTenths
    }

    private fun toBox(
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
        area: Int,
        kind: Char,
        xScale: Float,
        yScale: Float,
        fullW: Int,
        fullH: Int
    ): Box {
        val x = (minX * xScale).roundToInt().coerceIn(0, fullW - 1)
        val y = (minY * yScale).roundToInt().coerceIn(0, fullH - 1)
        val r = ((maxX + 1) * xScale).roundToInt().coerceIn(x + 1, fullW)
        val b = ((maxY + 1) * yScale).roundToInt().coerceIn(y + 1, fullH)
        return Box(x, y, r - x, b - y, area, kind)
    }

    /**
     * Gold and red dice are saturated. Tan clutter can cross the cut and then
     * stop, so a kept die has to contain a brighter core of the same hue.
     */
    private fun saturatedCore(core: BooleanArray?, pix: IntArray, n: Int, coreTenths: Int, minCore: Int): Boolean {
        if (core == null || n <= 0) return true
        var c = 0
        for (k in 0 until n) if (core[pix[k]]) c++
        return c >= minCore && c * 10 >= n * coreTenths
    }

    /** Two boxes are the same die when they share half of the smaller one. */
    private fun overlapsDie(a: Box, b: Box): Boolean {
        val x0 = max(a.x, b.x)
        val y0 = max(a.y, b.y)
        val x1 = min(a.x + a.w, b.x + b.w)
        val y1 = min(a.y + a.h, b.y + b.h)
        if (x1 <= x0 || y1 <= y0) return false
        val inter = (x1 - x0) * (y1 - y0)
        val smaller = min(a.w * a.h, b.w * b.h)
        return inter * 2 >= smaller
    }

    /** A small blob whose center sits inside a larger die is a fringe, not a second die. */
    private fun dropContained(boxes: List<Box>): List<Box> {
        if (boxes.size < 2) return boxes
        return boxes.filterIndexed { i, box ->
            val cx = box.x + box.w / 2
            val cy = box.y + box.h / 2
            boxes.indices.none { j ->
                if (j == i) return@none false
                val other = boxes[j]
                val bigger = other.w * other.h > box.w * box.h
                bigger && cx >= other.x && cy >= other.y && cx < other.x + other.w && cy < other.y + other.h
            }
        }
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
                    if (aspect !in 0.55f..1.70f) continue
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
            if (aspect in 0.55f..1.70f) out += Box(x0, y0, w, h, area, kind)
        }
        return out
    }

    private fun close(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray =
        erode(dilate(mask, w, h, radius), w, h, radius)

    private fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy !in 0 until h) continue
                    val row = yy * w
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx in 0 until w) out[row + xx] = true
                    }
                }
            }
        }
        return out
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in radius until h - radius) {
            for (x in radius until w - radius) {
                var ok = true
                for (dy in -radius..radius) {
                    val row = (y + dy) * w
                    for (dx in -radius..radius) {
                        if (!mask[row + x + dx]) ok = false
                    }
                }
                out[y * w + x] = ok
            }
        }
        return out
    }

    /**
     * White pips are compact blobs of similar size on the top face. Specular
     * streaks and the bright bevel are longer, smaller, or pressed against the
     * rim, so they are not pips. A lone glare spot is not logged as a 1.
     */
    private fun countWhitePips(image: RgbImage, die: Box): PipRead {
        // The box is in full-resolution pixels. The 1600px image is only the
        // color mask used to find the box. Pips are counted on this crop.
        val crop = ImageOps.crop(image, die.x, die.y, die.w, die.h)
        val face0 = topFace(crop)
        val short = min(face0.width, face0.height)
        // A 3× enlargement of a face that is already ~100px turns extra glare into
        // pips (the gold 3 on 5ec3a0f2 became a 6). Enlarge only the small faces,
        // where a 3×3 open would otherwise erase the pips. Larger faces stay at
        // the full-resolution crop.
        val factor = when {
            short < 48 -> 4
            short < 80 -> 3
            else -> 1
        }
        val face = if (factor == 1) {
            face0
        } else {
            ImageOps.scale(face0, (face0.width * factor).coerceAtLeast(1), (face0.height * factor).coerceAtLeast(1))
        }
        val read = readBlobs(face)
        val pips = ArrayList<DiceDetector.PipMark>()
        for ((nx, ny) in read.points) {
            pips += DiceDetector.PipMark(
                x = ((die.x + nx * die.w) / image.width).toDouble(),
                y = ((die.y + ny * die.h) / image.height).toDouble(),
                r = ((min(die.w, die.h) * 0.08f) / max(image.width, image.height)).toDouble(),
                die = 0
            )
        }
        return PipRead(
            count = read.count,
            clear = read.clear,
            margin = read.margin,
            mark = DiceDetector.DieMark(die.x, die.y, die.w, die.h, read.count),
            pips = pips,
            sizeLog = "crop ${die.w}x${die.h} face ${face0.width}x${face0.height} up ${factor}x -> ${face.width}x${face.height} clear=${read.clear}"
        )
    }

    /** A tall or wide box includes a side face. Keep the square that holds the white pips. */
    private fun topFace(crop: RgbImage): RgbImage {
        val side = min(crop.width, crop.height)
        if (crop.height > crop.width * 1.15f) {
            var bestY = 0
            var best = -1
            val step = max(1, side / 12)
            var y0 = 0
            while (y0 + side <= crop.height) {
                val score = brightCount(crop, 0, y0, side, side)
                if (score > best) {
                    best = score
                    bestY = y0
                }
                y0 += step
            }
            return ImageOps.crop(crop, 0, bestY, side, side)
        }
        if (crop.width > crop.height * 1.15f) {
            var bestX = 0
            var best = -1
            val step = max(1, side / 12)
            var x0 = 0
            while (x0 + side <= crop.width) {
                val score = brightCount(crop, x0, 0, side, side)
                if (score > best) {
                    best = score
                    bestX = x0
                }
                x0 += step
            }
            return ImageOps.crop(crop, bestX, 0, side, side)
        }
        return crop
    }

    private fun brightCount(image: RgbImage, x: Int, y: Int, w: Int, h: Int): Int {
        var n = 0
        val x1 = min(image.width, x + w)
        val y1 = min(image.height, y + h)
        for (yy in y until y1) {
            val row = yy * image.width
            for (xx in x until x1) {
                val i = row + xx
                val mn = min(image.red(i), min(image.green(i), image.blue(i)))
                if (mn >= 210) n++
            }
        }
        return n
    }

    private class Blob(val area: Int, val nx: Float, val ny: Float, val aspect: Float, val fill: Float)

    private class BlobFace(
        val count: Int,
        val clear: Boolean,
        val margin: Float,
        val points: List<Pair<Float, Float>>,
        val ratio: Float = 9f
    )

    private fun readBlobs(face: RgbImage): BlobFace {
        val opened = readBlobsAt(face, 1)
        if (opened.count in 1..6) return opened
        // A fat glare streak can hide one pip of a 4. A wider open shrinks that
        // streak onto the pip. A face that already read stays as it was.
        val wider = readBlobsAt(face, 2)
        if (wider.count in 1..6) return wider
        // Glare can weld two pips into one blob and hide a six. The centers of
        // the round cores are still there. Only a face that read nothing gets here.
        val peaked = readPeaks(face)
        if (peaked.count == 6) return peaked
        // A washed five can hide one corner in the rim glare. The other four
        // cores (including the center) are still there. Only a face that read
        // nothing gets here.
        val five = readQuincunx(face)
        return if (five.count == 5) five else opened
    }

    /**
     * Centers of the white cores. A pip welded to its neighbor still has its own
     * center, which a connected-component count misses. Used only after the blob
     * count failed, and only a six is accepted.
     */
    private fun readPeaks(face: RgbImage): BlobFace {
        val w = face.width
        val h = face.height
        val nms = max(6, min(w, h) / 8)
        val minR = max(4, min(w, h) / 14)
        val nms2 = nms * nms
        var bestScore = -1e9f
        var best = BlobFace(0, false, 0f, emptyList())
        for (thr in intArrayOf(208, 222, 236)) {
            val dist = whiteDist(face, thr)
            val order = ArrayList<Int>()
            for (i in dist.indices) if (dist[i] in minR..10_000) order.add(i)
            order.sortByDescending { dist[it] }
            val maxR = if (order.isEmpty()) 0 else dist[order[0]]
            val peaks = ArrayList<Blob>()
            for (i in order) {
                // A shoulder of the brightest pip is smaller than the pip itself.
                if (dist[i] * 4 < maxR * 3) continue
                val x = i % w
                val y = i / w
                val nx = (x + 0.5f) / w
                val ny = (y + 0.5f) / h
                if (nx !in 0.08f..0.92f || ny !in 0.08f..0.92f) continue
                var close = false
                for (p in peaks) {
                    val dx = (p.nx - nx) * w
                    val dy = (p.ny - ny) * h
                    if (dx * dx + dy * dy < nms2) {
                        close = true
                        break
                    }
                }
                if (close) continue
                peaks += Blob(dist[i], nx, ny, 1f, 1f)
                if (peaks.size > 6) break
            }
            if (peaks.size != 6 || geometry(peaks) != 6) continue
            val inset = peaks.all { it.nx in 0.12f..0.86f && it.ny in 0.12f..0.86f }
            val score = 70f + (if (inset) 20f else 0f) + thr / 10f
            if (score > bestScore) {
                bestScore = score
                best = BlobFace(6, inset, if (inset) 1f else 0f, peaks.map { it.nx to it.ny })
            }
        }
        return best
    }

    /**
     * A five whose corner pip is dimmer than the other four. The bright cores
     * must include the center, and the three outer cores must sit on one ring.
     * The missing corner is the spot that balances that ring. A dimmer core is
     * accepted only when it is the only peak sitting there.
     */
    private fun readQuincunx(face: RgbImage): BlobFace {
        val w = face.width
        val h = face.height
        val nms = max(6, min(w, h) / 8)
        val nms2 = nms * nms
        val minR = max(3, min(w, h) / 22)
        for (thr in intArrayOf(208, 222, 236)) {
            val dist = whiteDist(face, thr)
            val order = ArrayList<Int>()
            for (i in dist.indices) {
                if (dist[i] !in minR..10_000) continue
                val nx = (i % w + 0.5f) / w
                val ny = (i / w + 0.5f) / h
                if (nx !in 0.08f..0.92f || ny !in 0.08f..0.92f) continue
                order.add(i)
            }
            order.sortByDescending { dist[it] }
            if (order.isEmpty()) continue
            val maxR = dist[order[0]]
            val peaks = ArrayList<Blob>()
            for (i in order) {
                if (dist[i] * 2 < maxR) continue
                val nx = (i % w + 0.5f) / w
                val ny = (i / w + 0.5f) / h
                var close = false
                for (p in peaks) {
                    val dx = (p.nx - nx) * w
                    val dy = (p.ny - ny) * h
                    if (dx * dx + dy * dy < nms2) {
                        close = true
                        break
                    }
                }
                if (close) continue
                peaks += Blob(dist[i], nx, ny, 1f, 1f)
                if (peaks.size > 8) break
            }
            val strong = peaks.filter { it.area * 4 >= maxR * 3 }
            if (strong.size != 4) continue
            val cx = strong.sumOf { it.nx.toDouble() }.toFloat() / 4f
            val cy = strong.sumOf { it.ny.toDouble() }.toFloat() / 4f
            val center = strong.minBy { hypot(it.nx, it.ny, cx, cy) }
            if (hypot(center.nx, center.ny, cx, cy) > 0.16f) continue
            val outers = strong.filter { it != center }
            val radii = outers.map { hypot(it.nx, it.ny, center.nx, center.ny) }
            if (radii.max() - radii.min() > 0.14f) continue
            val predX = center.nx - outers.sumOf { (it.nx - center.nx).toDouble() }.toFloat()
            val predY = center.ny - outers.sumOf { (it.ny - center.ny).toDouble() }.toFloat()
            if (predX !in 0.02f..0.98f || predY !in 0.02f..0.98f) continue
            val ring = radii.average().toFloat()
            val hits = peaks.filter { extra ->
                extra !in strong &&
                    hypot(extra.nx, extra.ny, predX, predY) <= 0.12f &&
                    abs(hypot(extra.nx, extra.ny, center.nx, center.ny) - ring) <= 0.14f
            }
            if (hits.size != 1 || geometry(strong + hits[0]) != 5) continue
            return BlobFace(5, false, 0f, (strong + hits[0]).map { it.nx to it.ny })
        }
        return BlobFace(0, false, 0f, emptyList())
    }

    /** Chessboard distance inside the white mask. A non-white pixel is 0. */
    private fun whiteDist(face: RgbImage, thr: Int): IntArray {
        val w = face.width
        val h = face.height
        val mask = BooleanArray(w * h)
        for (i in mask.indices) {
            val r = face.red(i)
            val g = face.green(i)
            val b = face.blue(i)
            val mn = min(r, min(g, b))
            val mx = max(r, max(g, b))
            mask[i] = mn >= thr && mx - mn < 78
        }
        val dist = IntArray(mask.size) { Int.MAX_VALUE }
        val qx = IntArray(mask.size)
        val qy = IntArray(mask.size)
        var qt = 0
        for (i in mask.indices) {
            if (!mask[i]) {
                dist[i] = 0
                qx[qt] = i % w
                qy[qt] = i / w
                qt++
            }
        }
        var qh = 0
        while (qh < qt) {
            val x = qx[qh]
            val y = qy[qh]
            val d = dist[y * w + x]
            qh++
            for (dy in -1..1) {
                val yy = y + dy
                if (yy !in 0 until h) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx
                    if (xx !in 0 until w) continue
                    val j = yy * w + xx
                    if (dist[j] != Int.MAX_VALUE) continue
                    dist[j] = d + 1
                    qx[qt] = xx
                    qy[qt] = yy
                    qt++
                }
            }
        }
        return dist
    }

    private fun hypot(nx: Float, ny: Float, cx: Float, cy: Float): Float {
        val dx = nx - cx
        val dy = ny - cy
        return sqrt(dx * dx + dy * dy)
    }

    private fun readBlobsAt(face: RgbImage, openRadius: Int): BlobFace {
        var bestScore = -1e9f
        var best = BlobFace(0, false, 0f, emptyList())
        var threeScore = -1e9f
        var three: BlobFace? = null
        val votes = IntArray(7)
        for (thr in intArrayOf(208, 222, 236)) {
            val blobs = whiteBlobs(face, thr, openRadius)
            if (blobs.isEmpty()) continue
            val big = blobs.maxOf { it.area }.coerceAtLeast(1)
            // Printed marks such as "49Y" are much smaller than a pip, so the size
            // ratio drops them. A highlight on the rim is a different problem: it
            // is bright enough to join the set and spoil a real face (the gold 3
            // picks up the corner of the die). If the full set is not a legal
            // face, try again with only the blobs that sit inside the face.
            var kept = blobs.filter { it.area >= 0.42f * big && it.aspect in 0.42f..2.4f && it.fill >= 0.42f }
            var count = geometry(kept)
            if (count !in 1..6) {
                val inner = kept.filter { it.nx in 0.12f..0.86f && it.ny in 0.12f..0.86f }
                val innerCount = geometry(inner)
                if (innerCount in 1..6) {
                    kept = inner
                    count = innerCount
                }
            }
            // A real 3 on a translucent die often has one or two extra bright spots
            // (the rim, or a pip showing through). Keep the three only when they are
            // the single collinear triple in the set.
            if (count !in 1..6 && kept.size in 4..6) {
                val line = onlyCollinearTriple(kept)
                if (line != null) {
                    kept = line
                    count = 3
                }
            }
            val ratio = if (kept.isEmpty()) 9f else kept.maxOf { it.area }.toFloat() / kept.minOf { it.area }.coerceAtLeast(1)
            val inset = kept.isNotEmpty() && kept.all { it.nx in 0.12f..0.86f && it.ny in 0.12f..0.86f }
            val low = whiteBlobs(face, thr - 22, openRadius).map { it.area }.sortedDescending()
            val runner = if (low.size >= 2) low[1].toFloat() / low[0].coerceAtLeast(1) else 0f
            val oneOk = if (count == 1 && kept.isNotEmpty()) {
                val p = kept[0]
                abs(p.nx - 0.5f) <= 0.12f && abs(p.ny - 0.5f) <= 0.12f && runner <= 0.20f
            } else {
                true
            }
            if (count in 1..6) votes[count]++
            // A 1 or a 2 has to sit well inside the face. The saturated 6+6
            // photo collapses into a 2 and a 1, and a wider rim would log that as 3.
            val clear = count in 1..6 && ratio <= 1.28f && inset && (count != 1 || oneOk)
            val score = (if (count in 1..6) 80f else 0f) + (if (clear) 30f else 0f) + thr / 10f - ratio * 8f
            val faceRead = BlobFace(count, clear, if (clear) (1f / ratio) else 0f, kept.map { it.nx to it.ny }, ratio)
            if (score > bestScore) {
                bestScore = score
                best = faceRead
            }
            // A low threshold can drop the end pip of a three and leave a clear
            // pair. The three is the same pips plus one near the rim, so it is
            // not marked clear and would otherwise lose to that pair.
            if (count == 3 && ratio <= 1.28f && score > threeScore) {
                threeScore = score
                three = faceRead
            }
        }
        val chosen = if (best.count == 2 && three != null) three else best
        return trustRimFace(chosen, votes)
    }

    /**
     * A 4, 5, or 6 on a translucent die often has a corner pip just outside the
     * strict inset, or one pip a little larger where the light hits. The same
     * count at two thresholds is still that face. A 1 or a 2 is not promoted:
     * those are how a blown-out six gets miscounted.
     */
    private fun trustRimFace(face: BlobFace, votes: IntArray): BlobFace {
        if (face.clear || face.count !in 3..6) return face
        if (votes[face.count] < 2) return face
        if (face.ratio > 1.70f || face.points.isEmpty()) return face
        val onFace = face.points.all { (x, y) -> x in 0.04f..0.93f && y in 0.04f..0.93f }
        if (!onFace) return face
        return BlobFace(face.count, true, 1f / face.ratio, face.points, face.ratio)
    }

    private fun whiteBlobs(face: RgbImage, thr: Int, openRadius: Int): List<Blob> {
        val w = face.width
        val h = face.height
        val mask = BooleanArray(w * h)
        for (i in mask.indices) {
            val r = face.red(i)
            val g = face.green(i)
            val b = face.blue(i)
            val mn = min(r, min(g, b))
            val mx = max(r, max(g, b))
            mask[i] = mn >= thr && mx - mn < 78
        }
        val opened = dilate(erode(mask, w, h, openRadius), w, h, openRadius)
        val minArea = max(16, (0.003f * w * h).toInt())
        val seen = BooleanArray(opened.size)
        val stack = IntArray(opened.size)
        val out = ArrayList<Blob>()
        for (start in opened.indices) {
            if (!opened[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var sumX = 0
            var sumY = 0
            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until h) continue
                    val row = yy * w
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val xx = x + dx
                        if (xx !in 0 until w) continue
                        val j = row + xx
                        if (seen[j] || !opened[j]) continue
                        seen[j] = true
                        stack[sp++] = j
                    }
                }
            }
            if (area < minArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            out += Blob(
                area = area,
                nx = sumX.toFloat() / area / w,
                ny = sumY.toFloat() / area / h,
                aspect = bw.toFloat() / bh,
                fill = area.toFloat() / (bw * bh)
            )
        }
        return out
    }

    /** Three pips of a 3, and only when no other triple in the set is also a 3. */
    private fun onlyCollinearTriple(blobs: List<Blob>): List<Blob>? {
        var found: List<Blob>? = null
        val n = blobs.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                for (k in j + 1 until n) {
                    val tri = listOf(blobs[i], blobs[j], blobs[k])
                    if (geometry(tri) != 3) continue
                    if (found != null) return null
                    found = tri
                }
            }
        }
        return found
    }

    /** Snap blob centers to a legal 1–6 layout. Anything else is not a face. */
    private fun geometry(blobs: List<Blob>): Int {
        val n = blobs.size
        if (n == 1) {
            val p = blobs[0]
            return if (p.nx in 0.28f..0.72f && p.ny in 0.22f..0.78f) 1 else 0
        }
        if (n == 2) {
            val d = hypot(blobs[0], blobs[1])
            return if (d in 0.28f..0.95f) 2 else 0
        }
        if (n == 3) {
            for (i in 0 until 3) {
                val a = blobs[(i + 1) % 3]
                val b = blobs[(i + 2) % 3]
                val mx = (a.nx + b.nx) / 2f
                val my = (a.ny + b.ny) / 2f
                val dx = blobs[i].nx - mx
                val dy = blobs[i].ny - my
                if (sqrt(dx * dx + dy * dy) <= 0.18f) return 3
            }
            return 0
        }
        if (n == 4) {
            val cx = blobs.sumOf { it.nx.toDouble() }.toFloat() / 4f
            val cy = blobs.sumOf { it.ny.toDouble() }.toFloat() / 4f
            val ds = blobs.map { d ->
                val dx = d.nx - cx
                val dy = d.ny - cy
                sqrt(dx * dx + dy * dy)
            }.sorted()
            return if (ds[0] >= 0.12f && ds[3] - ds[0] <= 0.20f) 4 else 0
        }
        if (n == 5) {
            val cx = blobs.sumOf { it.nx.toDouble() }.toFloat() / 5f
            val cy = blobs.sumOf { it.ny.toDouble() }.toFloat() / 5f
            val ds = blobs.map { d ->
                val dx = d.nx - cx
                val dy = d.ny - cy
                sqrt(dx * dx + dy * dy)
            }.sorted()
            return if (ds[0] <= 0.14f && ds[1] >= 0.14f) 5 else 0
        }
        if (n == 6) {
            val xs = blobs.map { it.nx }.sorted()
            val ys = blobs.map { it.ny }.sorted()
            if (grouped(xs) || grouped(ys)) return 6
            // A turned six is still two columns of three. The same grouping
            // test is tried after rotating the pips. An axis-aligned six
            // already returned above.
            val cx = blobs.sumOf { it.nx.toDouble() }.toFloat() / 6f
            val cy = blobs.sumOf { it.ny.toDouble() }.toFloat() / 6f
            val rx = FloatArray(6)
            val ry = FloatArray(6)
            for (step in 1 until 18) {
                val ang = step * Math.PI / 18.0
                val cos = kotlin.math.cos(ang).toFloat()
                val sin = kotlin.math.sin(ang).toFloat()
                for (i in 0 until 6) {
                    val dx = blobs[i].nx - cx
                    val dy = blobs[i].ny - cy
                    rx[i] = cos * dx - sin * dy
                    ry[i] = sin * dx + cos * dy
                }
                rx.sort()
                ry.sort()
                if (grouped(rx.toList()) || grouped(ry.toList())) return 6
            }
        }
        return 0
    }

    private fun grouped(v: List<Float>): Boolean =
        v[2] - v[0] <= 0.28f && v[5] - v[3] <= 0.28f && v[3] - v[2] >= 0.08f

    private fun hypot(a: Blob, b: Blob): Float {
        val dx = a.nx - b.nx
        val dy = a.ny - b.ny
        return sqrt(dx * dx + dy * dy)
    }
}
