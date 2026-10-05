package com.srrtracker

import com.srrtracker.detect.MotionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class MotionGateTest {
    private val w = MotionGate.W
    private val h = MotionGate.H

    private fun felt(): IntArray = IntArray(w * h) { 80 }

    private fun stamp(gray: IntArray, cx: Int, cy: Int, half: Int, value: Int) {
        for (y in (cy - half)..(cy + half)) {
            if (y !in 0 until h) continue
            for (x in (cx - half)..(cx + half)) {
                if (x !in 0 until w) continue
                gray[y * w + x] = value
            }
        }
    }

    @Test
    fun smallDieInTheBoxIsSeenAndAStillThrowLogsOnce() {
        val gate = MotionGate(sensitivity = 50, settleMs = 500)
        gate.running = true
        val empty = felt()
        assertFalse(gate.diceInBox(empty))
        assertFalse(gate.onFrame(empty, 0).shouldCapture)

        val landed = felt()
        stamp(landed, w / 2, h / 2, 6, 220)
        assertTrue("a 13px die should count as dice in the box", gate.diceInBox(landed))

        val moving = gate.onFrame(landed, 100)
        assertEquals(MotionGate.Phase.MOVING, moving.phase)
        assertFalse(moving.shouldCapture)

        val settling = gate.onFrame(landed, 160)
        assertEquals(MotionGate.Phase.SETTLING, settling.phase)
        val tooSoon = gate.onFrame(landed, 160 + 499)
        assertFalse(tooSoon.shouldCapture)

        val shot = gate.onFrame(landed, 160 + 500)
        assertTrue(shot.shouldCapture)
        assertEquals(MotionGate.Phase.HOLD, shot.phase)

        var extra = 0
        var t = 800L
        repeat(8) {
            if (gate.onFrame(landed, t).shouldCapture) extra++
            t += 50
        }
        assertEquals("the same settled dice must not be counted twice", 0, extra)
    }

    @Test
    fun aShortBumpDoesNotArmAnotherRoll() {
        val gate = MotionGate(sensitivity = 50, settleMs = 400, rearmMs = 200)
        gate.running = true
        val empty = felt()
        val landed = felt().also { stamp(it, w / 2, h / 2, 8, 230) }
        gate.onFrame(empty, 0)
        gate.onFrame(landed, 50)
        gate.onFrame(landed, 100)
        assertTrue(gate.onFrame(landed, 100 + 400).shouldCapture)

        val nudged = felt().also { stamp(it, w / 2 + 20, h / 2, 8, 230) }
        val bump = gate.onFrame(nudged, 700)
        assertFalse(bump.shouldCapture)
        val back = gate.onFrame(landed, 780)
        assertEquals(MotionGate.Phase.HOLD, back.phase)
        assertFalse(gate.onFrame(landed, 780 + 400).shouldCapture)
    }

    @Test
    fun aNewThrowAfterRealMotionLogsAgain() {
        val gate = MotionGate(sensitivity = 50, settleMs = 300, rearmMs = 200)
        gate.running = true
        val first = felt().also { stamp(it, 200, 160, 8, 230) }
        gate.onFrame(felt(), 0)
        gate.onFrame(first, 40)
        gate.onFrame(first, 80)
        val capturedAt = 80L + 300L
        assertTrue(gate.onFrame(first, capturedAt).shouldCapture)

        val throwAt = capturedAt + MotionGate.POST_CAPTURE_QUIET_MS
        val traveling = felt().also { stamp(it, 280, 180, 8, 230) }
        gate.onFrame(traveling, throwAt)
        val stillTravel = felt().also { stamp(it, 320, 190, 8, 230) }
        val armed = gate.onFrame(stillTravel, throwAt + 200)
        assertEquals(MotionGate.Phase.MOVING, armed.phase)

        val second = felt().also { stamp(it, 360, 200, 8, 210) }
        gate.onFrame(second, throwAt + 300)
        gate.onFrame(second, throwAt + 360)
        val shot = gate.onFrame(second, throwAt + 360 + 300)
        assertTrue(shot.shouldCapture)
    }

    @Test
    fun movementOutsideTheBoxDoesNotLog() {
        val gate = MotionGate(sensitivity = 50, settleMs = 200)
        gate.running = true
        val edge = felt()
        val x = min(12, w - 1)
        stamp(edge, x, 12, 6, 230)
        gate.onFrame(felt(), 0)
        val info = gate.onFrame(edge, 40)
        assertFalse(info.diceInBox)
        gate.onFrame(edge, 80)
        assertFalse(gate.onFrame(edge, 80 + 200).shouldCapture)
    }

    @Test
    fun diceAlreadyRestingLogWithoutAThrow() {
        val gate = MotionGate(sensitivity = 50, settleMs = 500)
        gate.running = true
        val resting = felt().also { stamp(it, w / 2, h / 2, 8, 230) }
        val seen = gate.onFrame(resting, 0)
        assertTrue(seen.diceInBox)
        assertFalse(seen.shouldCapture)
        assertEquals(MotionGate.Phase.SETTLING, seen.phase)
        assertEquals(MotionGate.Stage.DICE_SEEN, seen.stage)

        val settling = gate.onFrame(resting, 200)
        assertEquals(MotionGate.Stage.SETTLING, settling.stage)
        assertFalse(settling.shouldCapture)

        val shot = gate.onFrame(resting, 500)
        assertTrue("resting dice must be captured once they have been still", shot.shouldCapture)
        assertEquals(MotionGate.Phase.HOLD, shot.phase)
        assertEquals(MotionGate.Stage.CAPTURING, shot.stage)

        assertFalse(gate.onFrame(resting, 900).shouldCapture)
        assertFalse(gate.onFrame(resting, 2_000).shouldCapture)
    }

    @Test
    fun placedDiceBelowTheMotionSpikeStillLog() {
        val gate = MotionGate(sensitivity = 50, settleMs = 400)
        gate.running = true
        assertFalse(gate.onFrame(felt(), 0).shouldCapture)

        // 9x9 is enough to turn the box green, and too small to count as a throw.
        val placed = felt().also { stamp(it, w / 2, h / 2, 4, 220) }
        val seen = gate.onFrame(placed, 40)
        assertTrue(seen.diceInBox)
        assertFalse(seen.shouldCapture)
        assertEquals(MotionGate.Phase.SETTLING, seen.phase)
        assertEquals(MotionGate.Stage.DICE_SEEN, seen.stage)

        assertFalse(gate.onFrame(placed, 40 + 399).shouldCapture)
        val shot = gate.onFrame(placed, 40 + 400)
        assertTrue(shot.shouldCapture)
        assertEquals(MotionGate.Stage.CAPTURING, shot.stage)
        assertFalse(gate.onFrame(placed, 2_000).shouldCapture)
    }

    @Test
    fun removingTheDiceRearmsTheNextPlacement() {
        val gate = MotionGate(sensitivity = 50, settleMs = 300, rearmMs = 200)
        gate.running = true
        val first = felt().also { stamp(it, w / 2, h / 2, 8, 230) }
        gate.onFrame(first, 0)
        assertTrue(gate.onFrame(first, 300).shouldCapture)

        val empty = felt()
        val leaving = gate.onFrame(empty, 400)
        assertFalse(leaving.diceInBox)
        assertEquals(MotionGate.Phase.HOLD, leaving.phase)
        val ready = gate.onFrame(empty, 400 + MotionGate.EMPTY_REARM_MS)
        assertEquals(MotionGate.Phase.ARMED, ready.phase)
        assertEquals(MotionGate.Stage.WAITING, ready.stage)

        val second = felt().also { stamp(it, w / 2 + 40, h / 2, 8, 210) }
        gate.onFrame(second, 700)
        val still = gate.onFrame(second, 760)
        assertEquals(MotionGate.Phase.SETTLING, still.phase)
        val shot = gate.onFrame(second, 760 + 300)
        assertTrue("a new placement after the box was empty must log", shot.shouldCapture)
    }

    @Test
    fun aDifferentRestingArrangementLogsWithoutAnotherThrow() {
        val gate = MotionGate(sensitivity = 50, settleMs = 300, rearmMs = 500)
        gate.running = true
        val first = felt().also { stamp(it, 220, 170, 8, 230) }
        gate.onFrame(first, 0)
        assertTrue(gate.onFrame(first, 300).shouldCapture)

        val quietEnd = 300 + MotionGate.POST_CAPTURE_QUIET_MS
        assertFalse(gate.onFrame(first, quietEnd).shouldCapture)
        val remembered = gate.onFrame(first, quietEnd + MotionGate.HOLD_REF_MS)
        assertEquals(MotionGate.Phase.HOLD, remembered.phase)
        assertFalse(remembered.shouldCapture)

        val movedAt = quietEnd + MotionGate.HOLD_REF_MS + 40
        val moved = felt().also { stamp(it, 420, 220, 8, 230) }
        val bump = gate.onFrame(moved, movedAt)
        assertFalse(bump.shouldCapture)
        val changed = gate.onFrame(moved, movedAt + 40)
        assertEquals(MotionGate.Phase.SETTLING, changed.phase)
        assertTrue(changed.detail.contains("changed"))
        val shot = gate.onFrame(moved, movedAt + 40 + 300)
        assertTrue(shot.shouldCapture)
        assertEquals(MotionGate.Stage.CAPTURING, shot.stage)
    }

    @Test
    fun aTealRailAcrossTheBoxIsNotDice() {
        val gate = MotionGate(sensitivity = 50, settleMs = 200)
        gate.running = true
        val cloth = felt()
        val x0 = (gate.frame.left * w).toInt()
        val x1 = (gate.frame.right * w).toInt()
        val y0 = (gate.frame.top * h).toInt()
        val y1 = (gate.frame.bottom * h).toInt()
        for (y in (y1 - 36) until y1) {
            for (x in x0 until x1) cloth[y * w + x] = 20
        }
        assertFalse("a rail is darker than the cloth but it is not a die", gate.diceInBox(cloth))
        gate.onFrame(felt(), 0)
        gate.onFrame(cloth, 40)
        gate.onFrame(cloth, 80)
        assertFalse(gate.onFrame(cloth, 80 + 200).shouldCapture)
    }

    @Test
    fun colorCountOverridesABrightPatchThatIsNotTwoDice() {
        val gate = MotionGate(sensitivity = 50, settleMs = 200)
        gate.running = true
        val patch = felt().also { stamp(it, w / 2, h / 2, 10, 230) }
        gate.onFrame(felt(), 0, coloredDice = 0)
        gate.onFrame(patch, 40, coloredDice = 0)
        gate.onFrame(patch, 80, coloredDice = 0)
        assertFalse(gate.onFrame(patch, 80 + 200, coloredDice = 0).shouldCapture)

        val armed = MotionGate(sensitivity = 50, settleMs = 200)
        armed.running = true
        armed.onFrame(felt(), 0, coloredDice = 2)
        armed.onFrame(felt(), 40, coloredDice = 2)
        assertTrue(armed.onFrame(felt(), 40 + 200, coloredDice = 2).shouldCapture)
    }

    @Test
    fun aFailedCaptureTriesAgainWhileTheDiceStayStill() {
        val gate = MotionGate(sensitivity = 50, settleMs = 300)
        gate.running = true
        val resting = felt().also { stamp(it, w / 2, h / 2, 8, 220) }
        gate.onFrame(resting, 0)
        assertTrue(gate.onFrame(resting, 300).shouldCapture)

        gate.recheckSoon()
        val retry = gate.onFrame(resting, 300)
        assertFalse(retry.shouldCapture)
        assertEquals(MotionGate.Phase.SETTLING, retry.phase)
        val shot = gate.onFrame(resting, 600)
        assertTrue(shot.shouldCapture)
    }

    @Test
    fun aFreshThrowLandingOnTheSameSpotIsCaptured() {
        val gate = MotionGate(sensitivity = 50, settleMs = 300, rearmMs = 200)
        gate.running = true
        val spot = felt().also { stamp(it, w / 2, h / 2, 8, 230) }
        gate.onFrame(felt(), 0, coloredDice = 0)
        gate.onFrame(spot, 40, coloredDice = 2)
        gate.onFrame(spot, 80, coloredDice = 2)
        val capturedAt = 80L + 300L
        assertTrue(gate.onFrame(spot, capturedAt, coloredDice = 2).shouldCapture)
        assertFalse(gate.onFrame(spot, capturedAt + 500, coloredDice = 2).shouldCapture)

        val liftAt = capturedAt + MotionGate.POST_CAPTURE_QUIET_MS
        val empty = felt()
        val leaving = gate.onFrame(empty, liftAt, coloredDice = 0)
        assertEquals(MotionGate.Phase.HOLD, leaving.phase)
        val ready = gate.onFrame(empty, liftAt + MotionGate.EMPTY_REARM_MS, coloredDice = 0)
        assertEquals(MotionGate.Phase.ARMED, ready.phase)

        val backAt = liftAt + MotionGate.EMPTY_REARM_MS + 40
        gate.onFrame(spot, backAt, coloredDice = 2)
        val settling = gate.onFrame(spot, backAt + 40, coloredDice = 2)
        assertEquals(MotionGate.Phase.SETTLING, settling.phase)
        val shot = gate.onFrame(spot, backAt + 40 + 300, coloredDice = 2)
        assertTrue("a new throw on the same spot must be logged", shot.shouldCapture)
    }
}
