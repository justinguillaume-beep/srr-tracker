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
        assertTrue(gate.onFrame(first, 80 + 300).shouldCapture)

        val traveling = felt().also { stamp(it, 280, 180, 8, 230) }
        gate.onFrame(traveling, 500)
        val stillTravel = felt().also { stamp(it, 320, 190, 8, 230) }
        val armed = gate.onFrame(stillTravel, 500 + 200)
        assertEquals(MotionGate.Phase.MOVING, armed.phase)

        val second = felt().also { stamp(it, 360, 200, 8, 210) }
        gate.onFrame(second, 800)
        gate.onFrame(second, 860)
        val shot = gate.onFrame(second, 860 + 300)
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
}
