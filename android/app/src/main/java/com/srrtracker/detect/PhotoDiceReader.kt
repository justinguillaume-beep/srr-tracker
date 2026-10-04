package com.srrtracker.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads a real photo: find square-ish dice of any color, then count contrasting
 * round pips inside each one. Local contrast (CLAHE) and an adaptive threshold
 * stand in for a fixed global cutoff, so shadows, glare, and table texture
 * do not have to match a drawing.
 */
object PhotoDiceReader {
    fun read(image: RgbImage): DiceDetector.Detection {
        val t0 = System.nanoTime()
        val located = locateDice(image)
        if (located.size < 2) {
            val n = located.size
            val marks = located.map { DiceDetector.DieMark(it.x, it.y, it.w, it.h, -1) }
            return fail(
                if (n == 1) "found 1 die" else "found 0 dice",
                marks,
                image,
                t0
            )
        }
        val reads = located.map { countPips(image, it) }
        val marks = reads.map { it.mark }
        val counts = reads.map { it.count }
        val bad = reads.indexOfFirst { it.count !in 1..6 }
        if (bad >= 0) {
            val n = counts[bad]
            return fail("pip count $n invalid", marks, image, t0, counts = counts)
        }
        val leftRight = if (reads[0].mark.x <= reads[1].mark.x) intArrayOf(0, 1) else intArrayOf(1, 0)
        val c0 = counts[leftRight[0]]
        val c1 = counts[leftRight[1]]
        val consistent = reads.all { it.consistent && !it.glare }
        val conf = if (consistent) "high" else "medium"
        val pips = ArrayList<DiceDetector.PipMark>()
        for (di in 0..1) {
            val src = leftRight[di]
            for (p in reads[src].pips) pips.add(p.copy(die = di))
        }
        return DiceDetector.Detection(
            ok = true,
            total = c0 + c1,
            counts = listOf(c0, c1),
            confidence = conf,
            cost = null,
            margin = null,
            pips = pips,
            reason = null,
            ms = (System.nanoTime() - t0) / 1_000_000,
            dice = listOf(marks[leftRight[0]], marks[leftRight[1]])
        )
    }

    private fun fail(
        reason: String,
        marks: List<DiceDetector.DieMark>,
        image: RgbImage,
        t0: Long,
        counts: List<Int> = emptyList()
    ): DiceDetector.Detection {
        val ordered = marks.sortedBy { it.x }
        return DiceDetector.Detection(
            ok = false,
            total = null,
            counts = null,
            confidence = "none",
            cost = null,
            margin = null,
            pips = emptyList(),
            reason = reason,
            ms = (System.nanoTime() - t0) / 1_000_000,
            dice = ordered
        )
    }

    private class DieBox(val x: Int, val y: Int, val w: Int, val h: Int, val side: Int)

    private class PipRead(
        val count: Int,
        val consistent: Boolean,
        val glare: Boolean,
        val mark: DiceDetector.DieMark,
        val pips: List<DiceDetector.PipMark>
    )

    private fun locateDice(image: RgbImage): List<DieBox> {
        val maxDim = 480
        val long = max(image.width, image.height)
        val scale = if (long > maxDim) long.toFloat() / maxDim else 1f
        val ww = max(1, (image.width / scale).roundToInt())
        val hh = max(1, (image.height / scale).roundToInt())
        val small = if (ww == image.width && hh == image.height) image else ImageOps.scale(image, ww, hh)
        val table = borderMedian(small)
        val mask = BooleanArray(ww * hh)
        var fg = 0
        for (i in mask.indices) {
            if (isDiePixel(small.red(i), small.green(i), small.blue(i), table)) {
                mask[i] = true
                fg++
            }
        }
        if (fg < 20) return emptyList()
        closeMask(mask, ww, hh)
        val minSide = max(6.0, 16.0 / scale)
        val minArea = max(24, (0.35 * minSide * minSide).toInt())
        val maxArea = max(minArea + 1, ((ww * hh) * 0.45).toInt())
        var comps = components(mask, ww, hh, minArea, maxArea)
        comps = comps.flatMap { splitIfJoined(it, mask, ww, hh, minArea) }
        val squares = comps.mapNotNull { toSquare(it, ww, hh) }
        val picked = pickPair(squares) ?: return squares.take(1).map { it.toFull(scale, image.width, image.height) }
        return picked.map { it.toFull(scale, image.width, image.height) }
    }

    private class Comp(val x: Int, val y: Int, val w: Int, val h: Int, val area: Int, val cx: Double, val cy: Double)

    private class Square(val comp: Comp, val side: Double) {
        fun toFull(scale: Float, imageW: Int, imageH: Int): DieBox {
            val pad = (side * scale * 0.12f).roundToInt().coerceAtLeast(2)
            val x = (comp.x * scale).roundToInt() - pad
            val y = (comp.y * scale).roundToInt() - pad
            val r = ((comp.x + comp.w) * scale).roundToInt() + pad
            val b = ((comp.y + comp.h) * scale).roundToInt() + pad
            val x0 = x.coerceIn(0, imageW - 1)
            val y0 = y.coerceIn(0, imageH - 1)
            val x1 = r.coerceIn(x0 + 1, imageW)
            val y1 = b.coerceIn(y0 + 1, imageH)
            val sidePx = max(8, (side * scale).roundToInt())
            return DieBox(x0, y0, x1 - x0, y1 - y0, sidePx)
        }
    }

    private fun toSquare(c: Comp, w: Int, h: Int): Square? {
        if (c.w < 5 || c.h < 5) return null
        val aspect = max(c.w, c.h).toDouble() / min(c.w, c.h)
        if (aspect > 1.9) return null
        val fill = c.area.toDouble() / (c.w * c.h)
        if (fill < 0.40) return null
        if (c.x <= 1 || c.y <= 1 || c.x + c.w >= w - 1 || c.y + c.h >= h - 1) {
            if (fill < 0.6) return null
        }
        return Square(c, sqrt(c.area.toDouble()) / 0.86)
    }

    private fun pickPair(squares: List<Square>): List<Square>? {
        if (squares.size < 2) return null
        var best: List<Square>? = null
        var bestScore = Double.MAX_VALUE
        for (i in squares.indices) {
            for (j in i + 1 until squares.size) {
                val a = squares[i]
                val b = squares[j]
                val areaRatio = max(a.comp.area, b.comp.area).toDouble() / min(a.comp.area, b.comp.area)
                if (areaRatio > 3.2) continue
                val size = max(a.side, b.side)
                val dist = kotlin.math.hypot(a.comp.cx - b.comp.cx, a.comp.cy - b.comp.cy)
                if (dist < size * 0.35 || dist > size * 7.0) continue
                val score = dist / size + areaRatio
                if (score < bestScore) {
                    bestScore = score
                    best = listOf(a, b)
                }
            }
        }
        return best
    }

    private fun splitIfJoined(c: Comp, mask: BooleanArray, w: Int, h: Int, minArea: Int): List<Comp> {
        val aspect = max(c.w, c.h).toDouble() / min(c.w, c.h).coerceAtLeast(1)
        if (aspect < 1.45) return listOf(c)
        val horizontal = c.w >= c.h
        val length = if (horizontal) c.w else c.h
        val proj = IntArray(length)
        for (yy in c.y until c.y + c.h) {
            val row = yy * w
            for (xx in c.x until c.x + c.w) {
                if (!mask[row + xx]) continue
                val i = if (horizontal) xx - c.x else yy - c.y
                proj[i]++
            }
        }
        val a = (length * 0.30).toInt()
        val b = (length * 0.70).toInt().coerceAtMost(length - 1)
        if (b <= a) return listOf(c)
        var valley = a
        var valleyV = Int.MAX_VALUE
        for (i in a..b) {
            if (proj[i] < valleyV) {
                valleyV = proj[i]
                valley = i
            }
        }
        val shoulder = max(proj[a / 2], proj[(length + b) / 2].coerceAtMost(length - 1))
        if (shoulder <= 0 || valleyV > shoulder * 0.55) return listOf(c)
        val left = subComp(c, mask, w, h, horizontal, c.x, c.y, if (horizontal) valley else c.w, if (horizontal) c.h else valley, minArea)
        val rightStartX = if (horizontal) c.x + valley else c.x
        val rightStartY = if (horizontal) c.y else c.y + valley
        val rightW = if (horizontal) c.w - valley else c.w
        val rightH = if (horizontal) c.h else c.h - valley
        val right = subComp(c, mask, w, h, horizontal, rightStartX, rightStartY, rightW, rightH, minArea)
        if (left == null || right == null) return listOf(c)
        return listOf(left, right)
    }

    private fun subComp(parent: Comp, mask: BooleanArray, w: Int, h: Int, horizontal: Boolean, x: Int, y: Int, bw: Int, bh: Int, minArea: Int): Comp? {
        if (bw < 4 || bh < 4) return null
        var area = 0
        var sx = 0L
        var sy = 0L
        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0
        for (yy in y until min(h, y + bh)) {
            val row = yy * w
            for (xx in x until min(w, x + bw)) {
                if (!mask[row + xx]) continue
                area++
                sx += xx
                sy += yy
                if (xx < minX) minX = xx
                if (xx > maxX) maxX = xx
                if (yy < minY) minY = yy
                if (yy > maxY) maxY = yy
            }
        }
        if (area < minArea || maxX < minX) return null
        return Comp(minX, minY, maxX - minX + 1, maxY - minY + 1, area, sx.toDouble() / area, sy.toDouble() / area)
    }

    private fun countPips(image: RgbImage, die: DieBox): PipRead {
        val crop = ImageOps.crop(image, die.x, die.y, die.w, die.h)
        val target = 168
        val short = min(crop.width, crop.height).coerceAtLeast(1)
        val scale = target.toFloat() / short
        val nw = max(8, (crop.width * scale).roundToInt())
        val nh = max(8, (crop.height * scale).roundToInt())
        val up = if (nw == crop.width && nh == crop.height) crop else ImageOps.scale(crop, nw, nh)
        val gray = IntArray(nw * nh) { i ->
            (up.red(i) * 77 + up.green(i) * 150 + up.blue(i) * 29) shr 8
        }
        val enhanced = clahe(gray, nw, nh)
        val dark = countPolarity(enhanced, nw, nh, die, image, darkPips = true)
        val light = countPolarity(enhanced, nw, nh, die, image, darkPips = false)
        return if (scoreRead(dark) >= scoreRead(light)) dark else light
    }

    private fun scoreRead(r: PipRead): Int {
        if (r.count !in 1..6) return if (r.count == 0) -400 else -40 - r.count
        return 200 + if (r.consistent) 20 else 0 - kotlin.math.abs(r.count - 3)
    }

    private data class RawBlob(val cx: Double, val cy: Double, val area: Int, val w: Int, val h: Int)

    private fun countPolarity(
        gray: IntArray,
        w: Int,
        h: Int,
        die: DieBox,
        full: RgbImage,
        darkPips: Boolean
    ): PipRead {
        var bright = 0
        for (v in gray) if (v >= 248) bright++
        val glare = bright > gray.size * 0.008
        val radius = max(5, min(w, h) / 7)
        val mean = boxMean(gray, w, h, radius)
        val mask = BooleanArray(gray.size)
        val marginX = (w * 0.08f).toInt()
        val marginY = (h * 0.08f).toInt()
        for (y in marginY until h - marginY) {
            val row = y * w
            for (x in marginX until w - marginX) {
                val i = row + x
                val delta = mean[i] - gray[i]
                val hit = if (darkPips) delta > 12 else -delta > 12
                if (hit) mask[i] = true
            }
        }
        val blobs = blobs(mask, w, h)
        val side = min(w, h).toDouble()
        val expect = Math.PI * (0.09 * side) * (0.09 * side)
        val sized = blobs.filter { b ->
            val aspect = max(b.w, b.h).toDouble() / min(b.w, b.h).coerceAtLeast(1)
            val fill = b.area.toDouble() / (b.w * b.h).coerceAtLeast(1)
            aspect <= 1.75 && fill >= 0.40 && b.area > expect * 0.20 && b.area < expect * 3.4
        }
        val kept = consistentSizes(sized)
        val count = kept.size
        val areas = kept.map { it.area }
        val consistent = if (areas.isEmpty()) false else {
            val hi = areas.max()
            val lo = areas.min().coerceAtLeast(1)
            hi.toDouble() / lo <= 2.8
        }
        val pips = kept.map { b ->
            val fx = die.x + b.cx / w * die.w
            val fy = die.y + b.cy / h * die.h
            DiceDetector.PipMark(
                x = fx / full.width,
                y = fy / full.height,
                r = (sqrt(b.area.toDouble()) * 0.5) / w * die.w / max(full.width, full.height),
                die = 0
            )
        }
        return PipRead(
            count = count,
            consistent = consistent && count in 1..6,
            glare = glare,
            mark = DiceDetector.DieMark(die.x, die.y, die.w, die.h, count),
            pips = pips
        )
    }

    private fun consistentSizes(blobs: List<RawBlob>): List<RawBlob> {
        if (blobs.size <= 1) return blobs
        val med = blobs.map { it.area }.sorted()[blobs.size / 2]
        val close = blobs.filter { it.area > med * 0.40 && it.area < med * 2.4 }
        return if (close.size in 1..6) close else blobs
    }

    private fun blobs(mask: BooleanArray, w: Int, h: Int): List<RawBlob> {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val out = ArrayList<RawBlob>()
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
                fun push(nx: Int, ny: Int) {
                    if (nx !in 0 until w || ny !in 0 until h) return
                    val ni = ny * w + nx
                    if (seen[ni] || !mask[ni]) return
                    seen[ni] = true
                    stack[sp++] = ni
                }
                push(x - 1, y)
                push(x + 1, y)
                push(x, y - 1)
                push(x, y + 1)
            }
            if (area >= 6) {
                out.add(RawBlob(sx.toDouble() / area, sy.toDouble() / area, area, maxX - minX + 1, maxY - minY + 1))
            }
        }
        return out
    }

    private fun isDiePixel(r: Int, g: Int, b: Int, table: IntArray): Boolean {
        val chroma = chroma(r, g, b, table[0], table[1], table[2])
        val bright = (r + g + b) - (table[0] + table[1] + table[2])
        if (chroma >= 36) return true
        return chroma >= 18 && bright > 70
    }

    private fun chroma(r: Int, g: Int, b: Int, tr: Int, tg: Int, tb: Int): Int {
        val s = (r + g + b).coerceAtLeast(1)
        val ts = (tr + tg + tb).coerceAtLeast(1)
        val cr = r * 255 / s
        val cg = g * 255 / s
        val cb = b * 255 / s
        val tcr = tr * 255 / ts
        val tcg = tg * 255 / ts
        val tcb = tb * 255 / ts
        return abs(cr - tcr) + abs(cg - tcg) + abs(cb - tcb)
    }

    private fun borderMedian(image: RgbImage): IntArray {
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        fun add(i: Int) {
            rs.add(image.red(i))
            gs.add(image.green(i))
            bs.add(image.blue(i))
        }
        val step = max(1, image.width / 80)
        var x = 0
        while (x < image.width) {
            add(x)
            add((image.height - 1) * image.width + x)
            x += step
        }
        var y = 0
        while (y < image.height) {
            add(y * image.width)
            add(y * image.width + image.width - 1)
            y += step
        }
        rs.sort()
        gs.sort()
        bs.sort()
        val m = rs.size / 2
        return intArrayOf(rs[m], gs[m], bs[m])
    }

    private fun closeMask(mask: BooleanArray, w: Int, h: Int) {
        val dil = mask.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (mask[i]) continue
                if (mask[i - 1] || mask[i + 1] || mask[i - w] || mask[i + w]) dil[i] = true
            }
        }
        for (i in mask.indices) mask[i] = dil[i]
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
                fun push(nx: Int, ny: Int) {
                    if (nx !in 0 until w || ny !in 0 until h) return
                    val ni = ny * w + nx
                    if (seen[ni] || !mask[ni]) return
                    seen[ni] = true
                    stack[sp++] = ni
                }
                push(x - 1, y)
                push(x + 1, y)
                push(x, y - 1)
                push(x, y + 1)
            }
            if (area in minArea..maxArea && maxX >= minX) {
                out.add(Comp(minX, minY, maxX - minX + 1, maxY - minY + 1, area, sx.toDouble() / area, sy.toDouble() / area))
            }
        }
        return out
    }

    private fun medianCenter(gray: IntArray, w: Int, h: Int): Int {
        val vals = ArrayList<Int>()
        val x0 = (w * 0.25f).toInt()
        val x1 = (w * 0.75f).toInt()
        val y0 = (h * 0.25f).toInt()
        val y1 = (h * 0.75f).toInt()
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1 step 2) vals.add(gray[row + x])
        }
        if (vals.isEmpty()) return 128
        vals.sort()
        return vals[vals.size / 2]
    }

    private fun clahe(gray: IntArray, w: Int, h: Int): IntArray {
        val tilesX = 4
        val tilesY = 4
        val tileW = (w + tilesX - 1) / tilesX
        val tileH = (h + tilesY - 1) / tilesY
        val hist = Array(tilesX * tilesY) { IntArray(256) }
        for (y in 0 until h) {
            val ty = (y / tileH).coerceAtMost(tilesY - 1)
            val row = y * w
            for (x in 0 until w) {
                val tx = (x / tileW).coerceAtMost(tilesX - 1)
                hist[ty * tilesX + tx][gray[row + x]]++
            }
        }
        val maps = Array(tilesX * tilesY) { IntArray(256) }
        for (t in hist.indices) {
            val hst = hist[t]
            val count = hst.sum().coerceAtLeast(1)
            val limit = max(1, (2.5 * count / 256.0).toInt())
            var excess = 0
            for (i in 0..255) {
                if (hst[i] > limit) {
                    excess += hst[i] - limit
                    hst[i] = limit
                }
            }
            val add = excess / 256
            var rem = excess - add * 256
            for (i in 0..255) hst[i] += add
            var k = 0
            while (rem > 0) {
                hst[k % 256]++
                rem--
                k++
            }
            var acc = 0
            for (b in 0..255) {
                acc += hst[b]
                maps[t][b] = (acc * 255.0 / count).toInt().coerceIn(0, 255)
            }
        }
        val out = IntArray(gray.size)
        for (y in 0 until h) {
            val fy = y.toDouble() / tileH - 0.5
            var y0 = kotlin.math.floor(fy).toInt()
            if (y0 < 0) y0 = 0
            if (y0 > tilesY - 1) y0 = tilesY - 1
            val y1 = (y0 + 1).coerceAtMost(tilesY - 1)
            val ty = (fy - y0).coerceIn(0.0, 1.0)
            val row = y * w
            for (x in 0 until w) {
                val fx = x.toDouble() / tileW - 0.5
                var x0 = kotlin.math.floor(fx).toInt()
                if (x0 < 0) x0 = 0
                if (x0 > tilesX - 1) x0 = tilesX - 1
                val x1 = (x0 + 1).coerceAtMost(tilesX - 1)
                val tx = (fx - x0).coerceIn(0.0, 1.0)
                val v = gray[row + x]
                val m00 = maps[y0 * tilesX + x0][v]
                val m10 = maps[y0 * tilesX + x1][v]
                val m01 = maps[y1 * tilesX + x0][v]
                val m11 = maps[y1 * tilesX + x1][v]
                val top = m00 + (m10 - m00) * tx
                val bot = m01 + (m11 - m01) * tx
                out[row + x] = (top + (bot - top) * ty).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private fun boxMean(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val stride = w + 1
        val ii = LongArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            val srcRow = y * w
            val below = (y + 1) * stride
            val above = y * stride
            for (x in 0 until w) {
                rowSum += src[srcRow + x]
                ii[below + x + 1] = ii[above + x + 1] + rowSum
            }
        }
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r).coerceAtMost(h - 1)
            val row = y * w
            for (x in 0 until w) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(w - 1)
                val sum = ii[(y1 + 1) * stride + (x1 + 1)] - ii[y0 * stride + (x1 + 1)] -
                    ii[(y1 + 1) * stride + x0] + ii[y0 * stride + x0]
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                out[row + x] = (sum / area).toInt()
            }
        }
        return out
    }
}
