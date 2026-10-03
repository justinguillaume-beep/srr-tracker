package com.srrtracker.detect

import kotlin.math.abs

/**
 * Watches a fixed-size gray preview for a throw.
 *
 * Small dice barely move the average of a whole frame, so motion is the count of
 * pixels inside the target box that actually changed. After a roll is logged the
 * gate waits for a real new movement before it will count again.
 */
class MotionGate(
    var sensitivity: Int = 50,
    var settleMs: Long = 500L,
    var rearmMs: Long = 200L
) {
    enum class Phase { PAUSED, ARMED, MOVING, SETTLING, HOLD }

    data class FrameInfo(
        val phase: Phase,
        val shouldCapture: Boolean,
        val diceInBox: Boolean,
        val roi: NormRect?
    )

    private val prev = IntArray(W * H)
    private val lastStill = IntArray(W * H)
    private var hasPrev = false
    private var hasStill = false
    private var phase = Phase.PAUSED
    private var stillSince = 0L
    private var holdMotionSince = -1L
    var running: Boolean = false
        set(value) {
            field = value
            if (!value) {
                phase = Phase.PAUSED
                holdMotionSince = -1L
            } else if (phase == Phase.PAUSED) {
                phase = Phase.ARMED
                stillSince = 0L
                holdMotionSince = -1L
            }
        }

    /** Difference that counts as a changed pixel, from the sensitivity slider. */
    private val minDiff: Int
        get() = (44 - sensitivity * 0.26f).toInt().coerceIn(14, 42)

    /** How many changed pixels mean "something moved". Tuned for a ~40px die in 1080p. */
    private val minHot: Int
        get() = (150 - sensitivity).coerceIn(40, 140)

    fun onFrame(gray: IntArray, nowMs: Long): FrameInfo {
        require(gray.size == W * H)
        val present = diceInBox(gray)
        val moving = if (!hasPrev) false else hotCount(prev, gray) >= minHot
        var capture = false
        var roi: NormRect? = null

        if (!running) {
            phase = Phase.PAUSED
            copy(gray, prev)
            copy(gray, lastStill)
            hasPrev = true
            hasStill = true
            return FrameInfo(Phase.PAUSED, false, present, null)
        }
        if (phase == Phase.PAUSED) phase = Phase.ARMED

        when (phase) {
            Phase.PAUSED -> phase = Phase.ARMED
            Phase.ARMED -> {
                if (moving) {
                    phase = Phase.MOVING
                } else {
                    copy(gray, lastStill)
                    hasStill = true
                }
            }
            Phase.MOVING -> {
                if (!moving) {
                    phase = Phase.SETTLING
                    stillSince = nowMs
                }
            }
            Phase.SETTLING -> {
                if (moving) {
                    phase = Phase.MOVING
                } else if (nowMs - stillSince >= settleMs) {
                    if (present) {
                        capture = true
                        phase = Phase.HOLD
                        holdMotionSince = -1L
                        roi = if (hasStill) changedRoi(lastStill, gray) else null
                    } else {
                        phase = Phase.ARMED
                        copy(gray, lastStill)
                        hasStill = true
                    }
                }
            }
            Phase.HOLD -> {
                if (moving) {
                    if (holdMotionSince < 0) holdMotionSince = nowMs
                    if (nowMs - holdMotionSince >= rearmMs) {
                        phase = Phase.MOVING
                        holdMotionSince = -1L
                    }
                } else {
                    holdMotionSince = -1L
                    copy(gray, lastStill)
                    hasStill = true
                }
            }
        }
        copy(gray, prev)
        hasPrev = true
        return FrameInfo(phase, capture, present, roi)
    }

    /** Camera failed after the dice had already settled — look again on the next frame. */
    fun recheckSoon() {
        if (!running) return
        phase = Phase.SETTLING
        stillSince = 0L
    }

    fun diceInBox(gray: IntArray): Boolean {
        val x0 = (FrameTarget.LEFT * W).toInt()
        val x1 = (FrameTarget.RIGHT * W).toInt()
        val y0 = (FrameTarget.TOP * H).toInt()
        val y1 = (FrameTarget.BOTTOM * H).toInt()
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
        return hot in 60..(area / 3)
    }

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
    }
}
