package com.srrtracker.detect

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pip counter ported from detector.js (grayscale, blur, multi-scale top-hat,
 * round blobs, two-die template fit).
 *
 * Small-dice changes, because a mounted phone sees dice ~40-80px in a 1080p frame:
 *  - [detectRoll] crops to the dice (color blobs, or the motion box) and upscales
 *    that crop so pips are large enough to count.
 *  - Background color is taken from the border, so a tight crop is not mistaken
 *    for felt.
 *  - Extra top-hat radii and slightly looser blob gates for pixel-sized pips.
 *  - The "pips are tiny" confidence penalty starts lower (4.5px, was 6.5).
 */
object DiceDetector {
    private val RADII = intArrayOf(2, 3, 4, 5, 6, 7, 8, 11, 15, 20, 27)
    private const val A = 0.27
    private val TEMPLATES: Array<Array<DoubleArray>> = arrayOf(
        emptyArray(),
        arrayOf(doubleArrayOf(0.0, 0.0)),
        arrayOf(doubleArrayOf(-A, -A), doubleArrayOf(A, A)),
        arrayOf(doubleArrayOf(-A, -A), doubleArrayOf(0.0, 0.0), doubleArrayOf(A, A)),
        arrayOf(doubleArrayOf(-A, -A), doubleArrayOf(A, -A), doubleArrayOf(-A, A), doubleArrayOf(A, A)),
        arrayOf(
            doubleArrayOf(-A, -A), doubleArrayOf(A, -A), doubleArrayOf(0.0, 0.0),
            doubleArrayOf(-A, A), doubleArrayOf(A, A)
        ),
        arrayOf(
            doubleArrayOf(-A, -A), doubleArrayOf(A, -A), doubleArrayOf(-A, 0.0),
            doubleArrayOf(A, 0.0), doubleArrayOf(-A, A), doubleArrayOf(A, A)
        )
    )
    private val RATIOS = doubleArrayOf(3.8, 4.2, 4.6, 5.0, 5.4, 5.8, 6.3)
    private const val MIN_SEP = 0.40
    private val COS = DoubleArray(60) { cos(it * 3.0 * PI / 180.0) }
    private val SIN = DoubleArray(60) { sin(it * 3.0 * PI / 180.0) }

    private const val MIN_BLOB_AREA = 8
    private const val MIN_ASPECT = 0.55
    private const val MAX_ASPECT = 1.80
    private const val MIN_FILL = 0.55
    private const val MAX_FILL = 0.95
    private const val TINY_PIP = 4.5

    data class PipMark(val x: Double, val y: Double, val r: Double, val die: Int)

    data class Detection(
        val ok: Boolean,
        val total: Int?,
        val counts: List<Int>?,
        val confidence: String,
        val cost: Double?,
        val margin: Double?,
        val pips: List<PipMark>,
        val hint: String = "",
        val reason: String? = null,
        val polarity: String? = null,
        val radius: Int? = null,
        val votes: Int = 0,
        val ms: Long = 0,
        val roi: IntRect? = null,
        val dice: List<DieMark> = emptyList()
    ) {
        val d1: Int? get() = counts?.getOrNull(0)
        val d2: Int? get() = counts?.getOrNull(1)
    }

    data class DieMark(val x: Int, val y: Int, val w: Int, val h: Int, val count: Int)

    class PipBlob(
        val x: Double,
        val y: Double,
        val area: Int,
        val d: Double,
        val contrast: Double,
        val fill: Double,
        val aspect: Double,
        var ring: DoubleArray? = null
    )

    /**
     * Count pips on a photo where the dice may be small.
     * [motionRoi] is the normalized box where the preview saw the throw.
     */
    fun detectRoll(image: RgbImage, motionRoi: NormRect? = null): Detection {
        val t0 = System.nanoTime()
        val colored = try {
            ColoredDiceReader.read(image, motionRoi)
        } catch (_: Throwable) {
            null
        }
        if (colored != null && colored.dice.isNotEmpty()) {
            return colored.copy(ms = (System.nanoTime() - t0) / 1_000_000)
        }
        val photo = try {
            PhotoDiceReader.read(image)
        } catch (_: Throwable) {
            null
        }
        if (photo != null && photo.ok && photo.confidence == "high" && photo.d1 != null && photo.d2 != null) {
            return photo.copy(ms = (System.nanoTime() - t0) / 1_000_000)
        }
        val classic = detectRollClassic(image, motionRoi)
        val classicMs = (System.nanoTime() - t0) / 1_000_000
        if (classic.ok && classic.confidence == "high") {
            return classic.copy(ms = classicMs, dice = photo?.dice ?: classic.dice)
        }
        if (photo != null && photo.ok) return photo.copy(ms = classicMs)
        if (classic.ok) return classic.copy(ms = classicMs, dice = photo?.dice ?: classic.dice)
        val failed = photo ?: classic
        return failed.copy(ms = classicMs, dice = photo?.dice ?: failed.dice, reason = photo?.reason ?: failed.reason)
    }

    private fun detectRollClassic(image: RgbImage, motionRoi: NormRect?): Detection {
        val t0 = System.nanoTime()
        val located = DiceLocator.locate(image)
        val motion = motionRoi?.takeIf { it.area() in 0.004f..0.45f }?.let { norm ->
            val x = (norm.left * image.width).toInt().coerceIn(0, image.width - 1)
            val y = (norm.top * image.height).toInt().coerceIn(0, image.height - 1)
            val r = (norm.right * image.width).toInt().coerceIn(x + 1, image.width)
            val b = (norm.bottom * image.height).toInt().coerceIn(y + 1, image.height)
            val sideGuess = max(24, min(r - x, b - y) / 2)
            ImageOps.pad(IntRect(x, y, r - x, b - y), image.width, image.height, sideGuess) to sideGuess
        }
        val primary: Pair<IntRect, Int> = when {
            located != null -> located.rect to located.dieSide
            motion != null -> motion
            else -> centerCrop(image)
        }
        val first = runOnRoi(image, primary.first, primary.second)
        if (first.ok) return first.copy(ms = (System.nanoTime() - t0) / 1_000_000, roi = primary.first)
        val alt = when {
            located != null && motion != null -> motion
            else -> centerCrop(image)
        }
        if (alt.first == primary.first) {
            return first.copy(ms = (System.nanoTime() - t0) / 1_000_000, roi = primary.first)
        }
        val second = runOnRoi(image, alt.first, alt.second)
        val pick = if (second.ok && (!first.ok || (second.cost ?: 9.0) < (first.cost ?: 9.0))) second else first
        val used = if (pick === second) alt.first else primary.first
        return pick.copy(ms = (System.nanoTime() - t0) / 1_000_000, roi = used)
    }

    private fun centerCrop(image: RgbImage): Pair<IntRect, Int> {
        val x = (image.width * FrameTarget.LEFT).toInt()
        val y = (image.height * FrameTarget.TOP).toInt()
        val r = (image.width * FrameTarget.RIGHT).toInt()
        val b = (image.height * FrameTarget.BOTTOM).toInt()
        return IntRect(x, y, max(1, r - x), max(1, b - y)) to 56
    }

    private fun runOnRoi(image: RgbImage, roi: IntRect, dieSide: Int): Detection {
        val cropped = ImageOps.crop(image, roi.x, roi.y, roi.w, roi.h)
        val longSide = max(cropped.width, cropped.height).toFloat()
        val want = if (dieSide > 0) 160f / dieSide else 1f
        val capped = if (longSide * want > 800f) 800f / longSide else want
        val scale = if (dieSide in 1..199) max(1f, capped) else capped.coerceIn(0.35f, 4f)
        val nw = max(1, round(cropped.width * scale).toInt())
        val nh = max(1, round(cropped.height * scale).toInt())
        val scaled = if (nw == cropped.width && nh == cropped.height) cropped else ImageOps.scale(cropped, nw, nh)
        val det = detect(scaled, maxDim = 800)
        if (!det.ok || det.counts == null) return det
        val pips = det.pips.map { p ->
            val px = (roi.x + p.x * cropped.width) / image.width
            val py = (roi.y + p.y * cropped.height) / image.height
            val rr = p.r * max(scaled.width, scaled.height) / scale / max(image.width, image.height)
            PipMark(px, py, rr, p.die)
        }
        return det.copy(pips = pips)
    }

    fun detect(image: RgbImage, maxDim: Int = 800): Detection {
        val t0 = System.nanoTime()
        val pr = prep(image, maxDim)
        val w = pr.w
        val h = pr.h
        val cands = ArrayList<Cand>(RADII.size * 2)
        for (pol in arrayOf("dark", "light")) {
            for (radius in RADII) {
                if (radius * 2 + 1 > min(w, h) / 2) continue
                val th = tophat(pr.gray, w, h, radius, pol == "dark")
                val blobs = filterConsistent(validateOnDie(extractBlobs(th, w, h, radius), pr))
                if (blobs.size < 2) {
                    cands.add(Cand(pol, radius, blobs.size, blobs, null))
                    continue
                }
                cands.add(Cand(pol, radius, blobs.size, blobs, fitDice(blobs)))
            }
        }
        val good = cands.filter { it.fit != null && it.fit.cost <= 0.075 }
        val pick: Cand? = if (good.isNotEmpty()) {
            good.sortedWith(compareByDescending<Cand> { it.n }.thenBy { it.fit!!.cost }).first()
        } else {
            cands.filter { it.fit != null }.minByOrNull { it.fit!!.cost }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (pick?.fit == null) {
            return Detection(
                ok = false, total = null, counts = null, confidence = "none",
                cost = null, margin = null, pips = emptyList(),
                reason = "fewer than 2 pips found", ms = ms
            )
        }
        val fit = pick.fit
        val counts = fit.groups.map { it.size }
        val cxs = fit.groups.map { g -> g.sumOf { pick.pips[it].x } / g.size }
        val order = if (cxs[0] <= cxs[1]) intArrayOf(0, 1) else intArrayOf(1, 0)
        val pips = ArrayList<PipMark>()
        for (di in 0..1) {
            val gi = order[di]
            for (i in fit.groups[gi]) {
                val p = pick.pips[i]
                pips.add(PipMark(p.x / w, p.y / h, p.d / 2.0 / max(w, h), di))
            }
        }
        val votes = good.count { it.n == pick.n }
        var conf = "low"
        if (fit.cost <= 0.06 && votes >= 3) conf = "high"
        else if (fit.cost <= 0.09 && votes >= 2) conf = "medium"
        var avgD = 0.0
        for (p in pick.pips) avgD += p.d
        avgD /= pick.pips.size
        var hint = ""
        if (avgD < TINY_PIP) {
            conf = "low"
            hint = "Pips look very small."
        }
        return Detection(
            ok = true,
            total = fit.total,
            counts = listOf(counts[order[0]], counts[order[1]]),
            confidence = conf,
            cost = fit.cost,
            margin = fit.margin,
            pips = pips,
            hint = hint,
            polarity = pick.pol,
            radius = pick.radius,
            votes = votes,
            ms = ms
        )
    }

    private class Cand(
        val pol: String,
        val radius: Int,
        val n: Int,
        val pips: List<PipBlob>,
        val fit: Fit?
    )

    internal class Fit(
        val cost: Double,
        val total: Int,
        val groups: List<List<Int>>,
        val margin: Double,
        val side: Double
    )

    private class Prep(
        val gray: IntArray,
        val w: Int,
        val h: Int,
        val cr: IntArray,
        val cg: IntArray,
        val cb: IntArray,
        val bg: DoubleArray
    )

    private fun prep(img: RgbImage, maxDim: Int): Prep {
        val srcW = img.width
        val srcH = img.height
        var f = max(srcW, srcH).toDouble() / maxDim
        if (f < 1.0) f = 1.0
        val w = max(1, jsRound(srcW / f))
        val h = max(1, jsRound(srcH / f))
        val g = FloatArray(w * h)
        val cr = IntArray(w * h)
        val cg = IntArray(w * h)
        val cb = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = floor(y * srcH.toDouble() / h).toInt()
            val y1 = max(y0 + 1, floor((y + 1) * srcH.toDouble() / h).toInt())
            for (x in 0 until w) {
                val x0 = floor(x * srcW.toDouble() / w).toInt()
                val x1 = max(x0 + 1, floor((x + 1) * srcW.toDouble() / w).toInt())
                var n = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (yy in y0 until y1) {
                    val row = yy * srcW
                    for (xx in x0 until x1) {
                        val p = img.pixels[row + xx]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        n++
                    }
                }
                val luma = (0.299 * sr + 0.587 * sg + 0.114 * sb) / n
                val i = y * w + x
                g[i] = luma.toFloat()
                cr[i] = sr / n
                cg[i] = sg / n
                cb[i] = sb / n
            }
        }
        val b = IntArray(w * h)
        for (y2 in 0 until h) {
            for (x2 in 0 until w) {
                var s2 = 0.0
                var n2 = 0
                for (dy in -1..1) {
                    val py = y2 + dy
                    if (py < 0 || py >= h) continue
                    for (dx in -1..1) {
                        val px = x2 + dx
                        if (px < 0 || px >= w) continue
                        s2 += g[py * w + px]
                        n2++
                    }
                }
                b[y2 * w + x2] = floor(s2 / n2 + 0.5).toInt()
            }
        }
        return Prep(b, w, h, cr, cg, cb, borderMedian(cr, cg, cb, w, h))
    }

    private fun borderMedian(cr: IntArray, cg: IntArray, cb: IntArray, w: Int, h: Int): DoubleArray {
        val band = max(2, min(w, h) / 28)
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        for (y in 0 until h step 2) {
            val edgeY = y < band || y >= h - band
            for (x in 0 until w step 2) {
                if (!edgeY && x >= band && x < w - band) continue
                val i = y * w + x
                rs.add(cr[i]); gs.add(cg[i]); bs.add(cb[i])
            }
        }
        fun med(a: List<Int>): Double {
            if (a.isEmpty()) return 128.0
            val s = a.sorted()
            return s[s.size / 2].toDouble()
        }
        return doubleArrayOf(med(rs), med(gs), med(bs))
    }

    private fun morph1D(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean, isMax: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val step = if (horizontal) 1 else w
        val lineStep = if (horizontal) w else 1
        val dq = IntArray(len + 1)
        for (l in 0 until lines) {
            val base = l * lineStep
            var head = 0
            var tail = 0
            var next = 0
            for (i in 0 until len) {
                val hi = min(len - 1, i + r)
                while (next <= hi) {
                    val v = src[base + next * step]
                    if (isMax) {
                        while (tail > head && src[base + dq[tail - 1] * step] <= v) tail--
                    } else {
                        while (tail > head && src[base + dq[tail - 1] * step] >= v) tail--
                    }
                    dq[tail++] = next
                    next++
                }
                val lo = i - r
                while (dq[head] < lo) head++
                dst[base + i * step] = src[base + dq[head] * step]
            }
        }
    }

    private fun morph(src: IntArray, w: Int, h: Int, r: Int, isMax: Boolean): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        morph1D(src, tmp, w, h, r, true, isMax)
        morph1D(tmp, out, w, h, r, false, isMax)
        return out
    }

    private fun tophat(gray: IntArray, w: Int, h: Int, r: Int, dark: Boolean): IntArray {
        val out = IntArray(w * h)
        if (dark) {
            val cl = morph(morph(gray, w, h, r, true), w, h, r, false)
            for (i in out.indices) {
                val d = cl[i] - gray[i]
                out[i] = if (d > 0) d else 0
            }
        } else {
            val op = morph(morph(gray, w, h, r, false), w, h, r, true)
            for (i in out.indices) {
                val d = gray[i] - op[i]
                out[i] = if (d > 0) d else 0
            }
        }
        return out
    }

    private fun extractBlobs(th: IntArray, w: Int, h: Int, radius: Int): List<PipBlob> {
        val hist = IntArray(256)
        for (v in th) hist[v]++
        val target = th.size * 0.002
        var acc = 0
        var p = 255
        while (p > 0) {
            acc += hist[p]
            if (acc >= target) break
            p--
        }
        val thr = max(24, jsRound(0.5 * p))
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val blobs = ArrayList<PipBlob>()
        var nl = 0
        val maxArea = PI * Math.pow(2.9 * radius + 2.0, 2.0) / 4.0 * 1.2
        val nPix = w * h
        for (s in 0 until nPix) {
            if (th[s] < thr || label[s] != 0) continue
            nl++
            var sp = 0
            stack[sp++] = s
            label[s] = nl
            var area = 0
            var sx = 0.0
            var sy = 0.0
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var border = false
            var con = 0.0
            while (sp > 0) {
                val q = stack[--sp]
                val x = q % w
                val y = q / w
                area++
                sx += x
                sy += y
                con += th[q]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                if (x > 0 && label[q - 1] == 0 && th[q - 1] >= thr) { label[q - 1] = nl; stack[sp++] = q - 1 }
                if (x < w - 1 && label[q + 1] == 0 && th[q + 1] >= thr) { label[q + 1] = nl; stack[sp++] = q + 1 }
                if (y > 0 && label[q - w] == 0 && th[q - w] >= thr) { label[q - w] = nl; stack[sp++] = q - w }
                if (y < h - 1 && label[q + w] == 0 && th[q + w] >= thr) { label[q + w] = nl; stack[sp++] = q + w }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (border || area < MIN_BLOB_AREA || area > maxArea) continue
            val aspect = bw.toDouble() / bh
            if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue
            val fill = area.toDouble() / (bw * bh)
            if (fill < MIN_FILL || fill > MAX_FILL) continue
            blobs.add(
                PipBlob(
                    x = sx / area,
                    y = sy / area,
                    area = area,
                    d = 2.0 * sqrt(area / PI),
                    contrast = con / area,
                    fill = fill,
                    aspect = aspect
                )
            )
        }
        return blobs
    }

    private fun ringInfo(b: PipBlob, pr: Prep): Pair<DoubleArray, Double>? {
        val rad = b.d / 2.0
        val rr = max(rad * 1.55, rad + 2.2)
        val n = 16
        val gs = ArrayList<Int>(n)
        val rs = ArrayList<Int>(n)
        val gch = ArrayList<Int>(n)
        val bs = ArrayList<Int>(n)
        for (k in 0 until n) {
            val ang = 2.0 * PI * k / n
            val x = jsRound(b.x + rr * cos(ang))
            val y = jsRound(b.y + rr * sin(ang))
            if (x < 0 || y < 0 || x >= pr.w || y >= pr.h) continue
            val i = y * pr.w + x
            gs.add(pr.gray[i])
            rs.add(pr.cr[i])
            gch.add(pr.cg[i])
            bs.add(pr.cb[i])
        }
        if (gs.size < n * 0.75) return null
        val rgb = doubleArrayOf(quantile(rs, 0.5), quantile(gch, 0.5), quantile(bs, 0.5))
        val spread = quantile(gs, 0.75) - quantile(gs, 0.25)
        return rgb to spread
    }

    private fun quantile(values: List<Int>, p: Double): Double {
        val s = values.sorted()
        val idx = min(s.size - 1, floor(p * s.size).toInt())
        return s[idx].toDouble()
    }

    private fun cdist(a: DoubleArray, b: DoubleArray): Double {
        val dr = a[0] - b[0]
        val dg = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun validateOnDie(blobs: List<PipBlob>, pr: Prep): List<PipBlob> {
        val out = ArrayList<PipBlob>()
        for (b in blobs) {
            val ri = ringInfo(b, pr) ?: continue
            b.ring = ri.first
            if (ri.second < 0.5 * b.contrast && cdist(ri.first, pr.bg) >= 45.0) out.add(b)
        }
        return out
    }

    private fun filterConsistent(blobs: List<PipBlob>): List<PipBlob> {
        if (blobs.size < 2) return blobs
        var best = emptyList<PipBlob>()
        for (i in blobs.indices) {
            val grp = ArrayList<PipBlob>()
            val ref = blobs[i]
            for (j in blobs.indices) {
                val o = blobs[j]
                val sizeOk = abs(o.d / ref.d - 1.0) <= 0.28
                val colorOk = ref.ring == null || o.ring == null || cdist(ref.ring!!, o.ring!!) < 70.0
                if (sizeOk && colorOk) grp.add(o)
            }
            if (grp.size > best.size) best = grp
        }
        if (best.size > 12) {
            best = best.sortedByDescending { it.contrast * (1.0 - abs(it.fill - 0.785)) }.take(12)
        }
        return best
    }

    internal fun fitDice(pips: List<PipBlob>): Fit? {
        val n = pips.size
        if (n < 2 || n > 12) return null
        var d = 0.0
        for (p in pips) d += p.d
        d /= n
        val sList = DoubleArray(RATIOS.size) { RATIOS[it] * d }
        val dist = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) dist[i][j] = hypot(pips[i].x - pips[j].x, pips[i].y - pips[j].y)
        }
        val cache = HashMap<Int, Double>()
        fun sse(mask: Int, si: Int): Double {
            val key = mask * 8 + si
            cache[key]?.let { return it }
            var count = 0
            for (k in 0 until n) if ((mask and (1 shl k)) != 0) count++
            val idx = IntArray(count)
            var t = 0
            for (k in 0 until n) if ((mask and (1 shl k)) != 0) idx[t++] = k
            var maxD = 0.0
            for (a in idx.indices) for (b in a + 1 until idx.size) {
                val dd = dist[idx[a]][idx[b]]
                if (dd > maxD) maxD = dd
            }
            val s = sList[si]
            val v = if (maxD > 0.95 * s) Double.POSITIVE_INFINITY else subsetSse(pips, idx, s)
            cache[key] = v
            return v
        }
        val full = (1 shl n) - 1
        data class Res(val mA: Int, val nA: Int, val nB: Int, val cost: Double, val si: Int)
        val results = ArrayList<Res>()
        val maskCount = 1 shl (n - 1)
        for (m in 0 until maskCount) {
            val mA = (m shl 1) or 1
            val mB = full xor mA
            if (mB == 0) continue
            val nA = Integer.bitCount(mA)
            val nB = n - nA
            if (nA > 6 || nB > 6 || nA < 1 || nB < 1) continue
            var bestC = Double.POSITIVE_INFINITY
            var bestS = -1
            for (si in sList.indices) {
                val s = sList[si]
                val a1 = sse(mA, si)
                if (a1 == Double.POSITIVE_INFINITY) continue
                val b1 = sse(mB, si)
                if (b1 == Double.POSITIVE_INFINITY) continue
                var pen = 0.0
                for (i in 0 until n) if ((mA and (1 shl i)) != 0) {
                    for (j in 0 until n) if ((mB and (1 shl j)) != 0) {
                        val lim = MIN_SEP * s
                        if (dist[i][j] < lim) pen += 2.0 * (lim - dist[i][j]) / s
                    }
                }
                val cost = sqrt((a1 + b1) / n) / s + pen + 0.015 * abs(ln(RATIOS[si] / 5.0))
                if (cost < bestC) {
                    bestC = cost
                    bestS = si
                }
            }
            if (bestS >= 0) results.add(Res(mA, nA, nB, bestC, bestS))
        }
        if (results.isEmpty()) return null
        results.sortBy { it.cost }
        val top = results[0]
        var altSplit = Double.POSITIVE_INFINITY
        val topKey = faceKey(top.nA, top.nB)
        for (i in 1 until results.size) {
            val key = faceKey(results[i].nA, results[i].nB)
            if (key != topKey) {
                altSplit = results[i].cost
                break
            }
        }
        val g0 = ArrayList<Int>()
        val g1 = ArrayList<Int>()
        for (i in 0 until n) {
            if ((top.mA and (1 shl i)) != 0) g0.add(i) else g1.add(i)
        }
        return Fit(top.cost, top.nA + top.nB, listOf(g0, g1), altSplit - top.cost, sList[top.si])
    }

    private fun faceKey(a: Int, b: Int): String = if (a <= b) "$a,$b" else "$b,$a"

    private fun subsetSse(pips: List<PipBlob>, idx: IntArray, s: Double): Double {
        val n = idx.size
        if (n == 1) return 0.0
        if (n !in TEMPLATES.indices) return Double.POSITIVE_INFINITY
        var cx = 0.0
        var cy = 0.0
        for (i in idx) {
            cx += pips[i].x
            cy += pips[i].y
        }
        cx /= n
        cy /= n
        val tpl = TEMPLATES[n]
        var best = Double.POSITIVE_INFINITY
        val dist = DoubleArray(n * n)
        val used = BooleanArray(n * 2)
        for (ti in COS.indices) {
            val c = COS[ti]
            val sn = SIN[ti]
            for (j in 0 until n) {
                val tx = cx + s * (tpl[j][0] * c - tpl[j][1] * sn)
                val ty = cy + s * (tpl[j][0] * sn + tpl[j][1] * c)
                for (i in 0 until n) {
                    val dx = pips[idx[i]].x - tx
                    val dy = pips[idx[i]].y - ty
                    dist[i * n + j] = dx * dx + dy * dy
                }
            }
            java.util.Arrays.fill(used, false)
            var sum = 0.0
            var abort = false
            for (k in 0 until n) {
                var bi = -1
                var bj = -1
                var bv = Double.POSITIVE_INFINITY
                for (i in 0 until n) if (!used[i]) {
                    for (j in 0 until n) if (!used[n + j] && dist[i * n + j] < bv) {
                        bv = dist[i * n + j]
                        bi = i
                        bj = j
                    }
                }
                if (bi < 0) {
                    abort = true
                    break
                }
                used[bi] = true
                used[n + bj] = true
                sum += bv
                if (sum >= best) {
                    abort = true
                    break
                }
            }
            if (!abort && sum < best) best = sum
        }
        return best
    }

    private fun jsRound(x: Double): Int = floor(x + 0.5).toInt()
}
