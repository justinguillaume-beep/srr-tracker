package com.srrtracker.detect

import kotlin.math.abs

/**
 * Decides when the camera should take a still of the dice.
 *
 * A throw (motion, then stillness) is enough, and so is dice that are simply
 * placed or already resting in the box. After a capture the gate waits until
 * the box is empty, the dice are thrown again, or the dice that are sitting
 * there have clearly changed. A short bump does not count as a new roll.
 */
class MotionGate(
    var sensitivity: Int = 50,
    var settleMs: Long = 500L,
    var rearmMs: Long = 200L
) {
    enum class Phase { PAUSED, ARMED, MOVING, SETTLING, HOLD }

    enum class Stage { PAUSED, WAITING, DICE_SEEN, SETTLING, CAPTURING, HOLD }

    data class FrameInfo(
        val phase: Phase,
        val shouldCapture: Boolean,
        val diceInBox: Boolean,
        val roi: NormRect?,
        val stage: Stage,
        val detail: String
    )

    private val prev = IntArray(W * H)
    private val lastStill = IntArray(W * H)
    private val holdRef = IntArray(W * H)
    private var hasPrev = false
    private var hasStill = false
    private var hasHoldRef = false
    private var phase = Phase.PAUSED
    private var stillSince = 0L
    private var holdMotionSince = -1L
    private var emptySince = -1L
    private var holdStableSince = -1L
    private var holdQuietUntil = 0L
    private var stampSettleOnNext = false

    /** The on-screen box. Detection and "dice in the box" use only this rectangle. */
    var frame: NormRect = CameraBox.asRect()

    var running: Boolean = false
        set(value) {
            field = value
            if (!value) {
                phase = Phase.PAUSED
                holdMotionSince = -1L
                emptySince = -1L
                stampSettleOnNext = false
            } else if (phase == Phase.PAUSED) {
                phase = Phase.ARMED
                stillSince = 0L
                holdMotionSince = -1L
                emptySince = -1L
                hasHoldRef = false
                holdStableSince = -1L
                stampSettleOnNext = false
            }
        }

    /** Difference that counts as a changed pixel, from the sensitivity slider. */
    private val minDiff: Int
        get() = (44 - sensitivity * 0.26f).toInt().coerceIn(14, 42)

    /** How many changed pixels mean "something moved". Tuned for a ~40px die in 1080p. */
    private val minHot: Int
        get() = (150 - sensitivity).coerceIn(40, 140)

    /** A new resting arrangement, bigger than a small bump or camera noise. */
    private val changeHot: Int
        get() = (minHot * 2).coerceAtLeast(180)

    /**
     * [coloredDice] is how many colored dice the preview locator saw in the box.
     * When it is passed, only a pair (exactly two) can arm a capture. Luminance
     * contrast alone is not dice: the teal rail is darker than the cloth and
     * used to look like a subject. Unit tests omit it and keep the luminance gate.
     */
    fun onFrame(gray: IntArray, nowMs: Long, coloredDice: Int? = null): FrameInfo {
        require(gray.size == W * H)
        val present = if (coloredDice != null) coloredDice == 2 else diceInBox(gray)
        val moving = if (!hasPrev) false else hotCount(prev, gray) >= minHot
        var capture = false
        var roi: NormRect? = null
        var detail = "waiting"

        if (!running) {
            phase = Phase.PAUSED
            copy(gray, prev)
            copy(gray, lastStill)
            hasPrev = true
            hasStill = true
            return info(Phase.PAUSED, false, present, null, Stage.PAUSED, "paused")
        }
        if (phase == Phase.PAUSED) phase = Phase.ARMED

        when (phase) {
            Phase.PAUSED -> phase = Phase.ARMED
            Phase.ARMED -> {
                if (present && !moving) {
                    phase = Phase.SETTLING
                    stillSince = nowMs
                    detail = "dice already still"
                } else if (moving) {
                    phase = Phase.MOVING
                    detail = if (present) "dice moving" else "motion, no dice yet"
                } else {
                    copy(gray, lastStill)
                    hasStill = true
                    detail = "waiting for dice"
                }
            }
            Phase.MOVING -> {
                if (!moving) {
                    if (present) {
                        phase = Phase.SETTLING
                        stillSince = nowMs
                        detail = "motion stopped, dice in the box"
                    } else {
                        phase = Phase.ARMED
                        copy(gray, lastStill)
                        hasStill = true
                        detail = "motion stopped, box empty"
                    }
                } else {
                    detail = if (present) "dice moving" else "motion, no dice yet"
                }
            }
            Phase.SETTLING -> {
                if (stampSettleOnNext || stillSince < 0L) {
                    stillSince = nowMs
                    stampSettleOnNext = false
                }
                if (moving) {
                    phase = Phase.MOVING
                    detail = "moved again while settling"
                } else if (!present) {
                    phase = Phase.ARMED
                    copy(gray, lastStill)
                    hasStill = true
                    detail = "box emptied while settling"
                } else if (nowMs - stillSince >= settleMs) {
                    capture = true
                    phase = Phase.HOLD
                    holdMotionSince = -1L
                    emptySince = -1L
                    hasHoldRef = false
                    holdStableSince = -1L
                    holdQuietUntil = nowMs + POST_CAPTURE_QUIET_MS
                    roi = frame
                    detail = "still long enough, capture"
                } else {
                    detail = "settling"
                }
            }
            Phase.HOLD -> {
                val quiet = nowMs < holdQuietUntil
                if (!present) {
                    holdMotionSince = -1L
                    hasHoldRef = false
                    holdStableSince = -1L
                    if (emptySince < 0L) emptySince = nowMs
                    if (nowMs - emptySince >= EMPTY_REARM_MS) {
                        phase = Phase.ARMED
                        emptySince = -1L
                        copy(gray, lastStill)
                        hasStill = true
                        detail = "box empty, ready for the next roll"
                    } else {
                        detail = "box emptying"
                    }
                } else if (quiet) {
                    emptySince = -1L
                    holdMotionSince = -1L
                    holdStableSince = -1L
                    detail = "photo just taken"
                } else if (moving) {
                    emptySince = -1L
                    holdStableSince = -1L
                    if (holdMotionSince < 0L) holdMotionSince = nowMs
                    if (nowMs - holdMotionSince >= rearmMs) {
                        phase = Phase.MOVING
                        holdMotionSince = -1L
                        hasHoldRef = false
                        detail = "new throw"
                    } else {
                        detail = "short bump, still the same roll"
                    }
                } else if (hasHoldRef && boxDiff(holdRef, gray) >= changeHot) {
                    emptySince = -1L
                    holdMotionSince = -1L
                    hasHoldRef = false
                    holdStableSince = -1L
                    phase = Phase.SETTLING
                    stillSince = nowMs
                    detail = "dice changed since the last photo"
                } else {
                    emptySince = -1L
                    holdMotionSince = -1L
                    if (!hasHoldRef) {
                        if (holdStableSince < 0L) holdStableSince = nowMs
                        if (nowMs - holdStableSince >= HOLD_REF_MS) {
                            copy(gray, holdRef)
                            hasHoldRef = true
                        }
                    }
                    copy(gray, lastStill)
                    hasStill = true
                    detail = "same dice, waiting for them to leave or change"
                }
            }
        }

        copy(gray, prev)
        hasPrev = true
        val stage = stageFor(capture, present, nowMs)
        if (detail == "waiting") {
            detail = when (stage) {
                Stage.DICE_SEEN -> "dice seen"
                Stage.SETTLING -> "settling"
                Stage.CAPTURING -> "capture"
                Stage.HOLD -> "holding"
                Stage.PAUSED -> "paused"
                Stage.WAITING -> "waiting for dice"
            }
        }
        return info(phase, capture, present, roi, stage, detail)
    }

    /**
     * The photo failed after the dice had already settled. Wait out another
     * settle period, then try again. No new throw is required.
     */
    fun recheckSoon() {
        if (!running) return
        phase = Phase.SETTLING
        stillSince = -1L
        stampSettleOnNext = true
        holdMotionSince = -1L
        emptySince = -1L
        hasHoldRef = false
        holdStableSince = -1L
    }

    /** A manual Count now just took a photo. Do not immediately take another. */
    fun holdAfterManual() {
        if (!running) return
        phase = Phase.HOLD
        holdMotionSince = -1L
        emptySince = -1L
        hasHoldRef = false
        holdStableSince = -1L
        holdQuietUntil = 0L
        stampSettleOnNext = false
    }

    fun diceInBox(gray: IntArray): Boolean = subjectIn(gray, frame)

    /** True when [roi] on a 640×360 gray frame holds a subject darker or lighter than its border. */
    fun subjectIn(gray: IntArray, roi: NormRect): Boolean {
        require(gray.size == W * H)
        val x0 = (roi.left * W).toInt().coerceIn(0, W - 1)
        val x1 = (roi.right * W).toInt().coerceIn(x0 + 1, W)
        val y0 = (roi.top * H).toInt().coerceIn(0, H - 1)
        val y1 = (roi.bottom * H).toInt().coerceIn(y0 + 1, H)
        if (x1 <= x0 || y1 <= y0) return false
        val border = ArrayList<Int>(((x1 - x0) + (y1 - y0)) / 2 + 8)
        var x = x0
        while (x < x1) {
            border.add(gray[y0 * W + x])
            border.add(gray[(y1 - 1) * W + x])
            x += 3
        }
        var y = y0
        while (y < y1) {
            border.add(gray[y * W + x0])
            border.add(gray[y * W + (x1 - 1)])
            y += 3
        }
        if (border.isEmpty()) return false
        val sorted = border.sorted()
        val bg = sorted[sorted.size / 2]
        var hot = 0
        val area = (x1 - x0) * (y1 - y0)
        for (yy in y0 until y1) {
            val row = yy * W
            for (xx in x0 until x1) {
                if (abs(gray[row + xx] - bg) > 34) hot++
            }
        }
        if (hot !in 60..(area / 3)) return false
        // A rail or a cloth wrinkle can pass the pixel count. A die is a compact
        // blob, not a band that runs across the box.
        return compactSubjects(gray, x0, x1, y0, y1, bg) > 0
    }

    private fun compactSubjects(gray: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, bg: Int): Int {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 8 || rh < 8) return 0
        val mask = BooleanArray(rw * rh)
        for (yy in y0 until y1) {
            val src = yy * W
            val row = (yy - y0) * rw
            for (xx in x0 until x1) {
                if (abs(gray[src + xx] - bg) > 34) mask[row + (xx - x0)] = true
            }
        }
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var dice = 0
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var minX = rw
            var minY = rh
            var maxX = 0
            var maxY = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % rw
                val y = i / rw
                area++
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (x > 0) sp = push(mask, seen, stack, sp, i - 1)
                if (x + 1 < rw) sp = push(mask, seen, stack, sp, i + 1)
                if (y > 0) sp = push(mask, seen, stack, sp, i - rw)
                if (y + 1 < rh) sp = push(mask, seen, stack, sp, i + rw)
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < 8 || bh < 8) continue
            val aspect = bw.toFloat() / bh
            if (aspect !in 0.45f..2.2f) continue
            if (area.toFloat() / (bw * bh) < 0.35f) continue
            if (bw > rw * 0.72f || bh > rh * 0.72f) continue
            dice++
        }
        return dice
    }

    private fun push(mask: BooleanArray, seen: BooleanArray, stack: IntArray, sp: Int, i: Int): Int {
        if (seen[i] || !mask[i]) return sp
        seen[i] = true
        stack[sp] = i
        return sp + 1
    }

    private fun stageFor(capture: Boolean, present: Boolean, nowMs: Long): Stage {
        if (capture) return Stage.CAPTURING
        return when (phase) {
            Phase.PAUSED -> Stage.PAUSED
            Phase.HOLD -> Stage.HOLD
            Phase.SETTLING -> if (nowMs - stillSince < SEEN_MS) Stage.DICE_SEEN else Stage.SETTLING
            Phase.MOVING -> if (present) Stage.DICE_SEEN else Stage.WAITING
            Phase.ARMED -> if (present) Stage.DICE_SEEN else Stage.WAITING
        }
    }

    private fun info(
        phase: Phase,
        capture: Boolean,
        present: Boolean,
        roi: NormRect?,
        stage: Stage,
        detail: String
    ) = FrameInfo(phase, capture, present, roi, stage, detail)

    private fun hotCount(a: IntArray, b: IntArray): Int {
        val x0 = (0.08f * W).toInt()
        val x1 = (0.92f * W).toInt()
        val y0 = (0.08f * H).toInt()
        val y1 = (0.92f * H).toInt()
        val diff = minDiff
        var n = 0
        for (y in y0 until y1) {
            val row = y * W
            for (x in x0 until x1) {
                if (abs(a[row + x] - b[row + x]) >= diff) n++
            }
        }
        return n
    }

    private fun boxDiff(a: IntArray, b: IntArray): Int {
        val x0 = (frame.left * W).toInt().coerceIn(0, W - 1)
        val x1 = (frame.right * W).toInt().coerceIn(x0 + 1, W)
        val y0 = (frame.top * H).toInt().coerceIn(0, H - 1)
        val y1 = (frame.bottom * H).toInt().coerceIn(y0 + 1, H)
        val diff = minDiff
        var n = 0
        for (y in y0 until y1) {
            val row = y * W
            for (x in x0 until x1) {
                if (abs(a[row + x] - b[row + x]) >= diff) n++
            }
        }
        return n
    }

    private fun changedRoi(base: IntArray, now: IntArray): NormRect? {
        val diff = minDiff
        var minX = W
        var minY = H
        var maxX = -1
        var maxY = -1
        var n = 0
        for (y in 0 until H) {
            val row = y * W
            for (x in 0 until W) {
                if (abs(base[row + x] - now[row + x]) >= diff) {
                    n++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (n < 40 || maxX < minX) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw > W * 0.8f || bh > H * 0.8f) return null
        val padX = bw * 0.25f
        val padY = bh * 0.25f
        val left = ((minX - padX) / W).coerceIn(0f, 1f)
        val top = ((minY - padY) / H).coerceIn(0f, 1f)
        val right = ((maxX + 1 + padX) / W).coerceIn(0f, 1f)
        val bottom = ((maxY + 1 + padY) / H).coerceIn(0f, 1f)
        if (right - left < 0.02f || bottom - top < 0.02f) return null
        return NormRect(left, top, right, bottom)
    }

    private fun copy(src: IntArray, dst: IntArray) {
        System.arraycopy(src, 0, dst, 0, src.size)
    }

    companion object {
        const val W = 640
        const val H = 360
        const val EMPTY_REARM_MS = 120L
        const val HOLD_REF_MS = 300L
        const val POST_CAPTURE_QUIET_MS = 350L
        private const val SEEN_MS = 150L
    }
}
