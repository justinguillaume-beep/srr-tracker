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

    fun read(image: RgbImage, roi: NormRect? = null): DiceDetector.Detection {
        lastSizeLog = ""
        val t0 = System.nanoTime()
        val bounds = pixelRoi(image, roi)
        val work = if (bounds.x == 0 && bounds.y == 0 && bounds.w == image.width && bounds.h == image.height) {
            image
        } else {
            ImageOps.crop(image, bounds.x, bounds.y, bounds.w, bounds.h)
        }
        val located = locate(work).map { box ->
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

    private fun locate(image: RgbImage): List<Box> {
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
        val red = BooleanArray(sw * sh)
        for (i in purple.indices) {
            val r = small.red(i)
            val g = small.green(i)
            val b = small.blue(i)
            val mx = max(r, max(g, b))
            val chroma = mx - min(r, min(g, b))
            val pur = min(r, b) - g
            val yel = min(r, g) - b
            if (pur > 14 && mx in 50..239 && chroma > 12) purple[i] = true
            else if (yel > 48 && mx > 90 && b < 165 && chroma > 40 && g > 80) yellow[i] = true
            else if (redDie(r, g, b, chroma)) red[i] = true
        }
        val boxes = ArrayList<Box>()
        boxes += components(close(purple, sw, sh, 1), sw, sh, 'P', scale, xScale, yScale, image.width, image.height, 0.32f)
        // Gold dice are translucent, so the yellow mask is full of pip holes.
        // A wider close joins those holes without lowering the yellow threshold.
        boxes += components(close(yellow, sw, sh, 3), sw, sh, 'Y', scale, xScale, yScale, image.width, image.height, 0.27f)
        // Red/pink dice are translucent too. Close radius 2 fills pip holes
        // without pulling in the warm grey cloth (that stays under the margin).
        boxes += components(close(red, sw, sh, 2), sw, sh, 'R', scale, xScale, yScale, image.width, image.height, 0.28f)
        return dropSizeOutliers(dropContained(mergeSameColor(boxes)))
    }

    /**
     * A red or pink die has R clearly above both G and B. Amber stays on the
     * yellow test (margin 48, so ivory near 46 is not taken). Warm grey cloth
     * is only a few levels redder than green, which this margin rejects.
     */
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
        minFill: Float
    ): List<Box> {
        val minSide = (32f / scale).toInt().coerceIn(10, 48)
        val maxSide = (200f / scale).toInt().coerceIn(minSide + 8, 480)
        val minArea = (minSide * minSide * 0.35f).toInt().coerceAtLeast(40)
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
                    consider(out, span, kind, xScale, yScale, fullW, fullH, minSide, maxSide, minArea, maxArea, minFill)
                }
                continue
            }
            if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) continue
            if (area !in minArea..maxArea) continue
            val aspect = bw.toFloat() / bh
            val fill = area.toFloat() / (bw * bh)
            if (aspect !in 0.55f..1.80f || fill < minFill) continue
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
        minFill: Float
    ) {
        val bw = span.x1 - span.x0 + 1
        val bh = span.y1 - span.y0 + 1
        if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) return
        if (span.area !in minArea..maxArea) return
        val aspect = bw.toFloat() / bh
        val fill = span.area.toFloat() / (bw * bh)
        if (aspect !in 0.55f..1.80f || fill < minFill) return
        out += toBox(span.x0, span.y0, span.x1, span.y1, span.area, kind, xScale, yScale, fullW, fullH)
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
            sizeLog = "crop ${die.w}x${die.h} face ${face0.width}x${face0.height} up ${factor}x -> ${face.width}x${face.height}"
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
        val points: List<Pair<Float, Float>>
    )

    private fun readBlobs(face: RgbImage): BlobFace {
        var bestScore = -1e9f
        var best = BlobFace(0, false, 0f, emptyList())
        for (thr in intArrayOf(208, 222, 236)) {
            val blobs = whiteBlobs(face, thr)
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
            val low = whiteBlobs(face, thr - 22).map { it.area }.sortedDescending()
            val runner = if (low.size >= 2) low[1].toFloat() / low[0].coerceAtLeast(1) else 0f
            val oneOk = if (count == 1 && kept.isNotEmpty()) {
                val p = kept[0]
                abs(p.nx - 0.5f) <= 0.12f && abs(p.ny - 0.5f) <= 0.12f && runner <= 0.20f
            } else {
                true
            }
            val clear = count in 1..6 && ratio <= 1.28f && inset && (count != 1 || oneOk)
            val score = (if (count in 1..6) 80f else 0f) + (if (clear) 30f else 0f) + thr / 10f - ratio * 8f
            if (score > bestScore) {
                bestScore = score
                best = BlobFace(count, clear, if (clear) (1f / ratio) else 0f, kept.map { it.nx to it.ny })
            }
        }
        return best
    }

    private fun whiteBlobs(face: RgbImage, thr: Int): List<Blob> {
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
        val opened = dilate(erode(mask, w, h, 1), w, h, 1)
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
