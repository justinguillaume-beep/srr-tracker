package com.srrtracker

import com.srrtracker.detect.CaptureFraming
import com.srrtracker.detect.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A die that sits in the live preview must still sit in the saved still after
 * zoom, rotation, the view's aspect crop, and an already-applied sensor crop.
 */
class CaptureFramingTest {
    private val viewW = 990
    private val viewH = 591
    private val roi = NormRect(0.30f, 0.32f, 0.70f, 0.68f)

    @Test
    fun zoomRotationAndAspectKeepAPreviewDieInsideTheSavedStill() {
        val sensorW = 4000
        val sensorH = 3000
        val degrees = 90
        val (upW, upH) = CaptureFraming.uprightSize(sensorW, sensorH, degrees)
        assertEquals(3000, upW)
        assertEquals(4000, upH)

        // Upright center, which is the middle of the zoomed preview.
        val die = CaptureFraming.toUpright(2000, 1499, sensorW, sensorH, degrees)
        assertEquals(1500, die.first)
        assertEquals(2000, die.second)

        val preview = CaptureFraming.previewOnSensor(upW, upH, viewW, viewH, zoom = 2f)
        assertTrue("center die is on screen", preview.contains(die.first, die.second))

        val full = CaptureFraming.Px(0, 0, upW, upH)
        val framed = CaptureFraming.frame(upW, upH, full, upW, upH, viewW, viewH, 2f, roi)
        assertTrue(framed.crop.contains(die.first, die.second))
        val nx = (die.first - framed.crop.x).toFloat() / framed.crop.w
        val ny = (die.second - framed.crop.y).toFloat() / framed.crop.h
        assertTrue("die stays inside the mapped box at $nx,$ny roi=$roi", inside(framed.roi, nx, ny))

        // A die at the left edge of the preview is still in the saved crop.
        val edgeX = preview.x + 2
        val edgeY = preview.y + preview.h / 2
        assertTrue(framed.crop.contains(edgeX, edgeY))

        // The band the aspect crop removes is not in the saved still.
        val above = preview.y - 10
        assertTrue(above > 0)
        assertTrue(!framed.crop.contains(preview.x + preview.w / 2, above))
    }

    @Test
    fun anAlreadyCroppedPreviewIsNotZoomedAgain() {
        val upW = 3000
        val upH = 4000
        val preview = CaptureFraming.previewOnSensor(upW, upH, viewW, viewH, zoom = 2f)
        // The JPEG is already that preview. Applying zoom again would push an
        // edge die out of the frame. The saved crop must be the whole buffer.
        val framed = CaptureFraming.frame(
            bitmapW = preview.w,
            bitmapH = preview.h,
            sensorCrop = preview,
            uprightW = upW,
            uprightH = upH,
            viewW = viewW,
            viewH = viewH,
            zoom = 2f,
            roi = roi
        )
        assertEquals(0, framed.crop.x)
        assertEquals(0, framed.crop.y)
        assertEquals(preview.w, framed.crop.w)
        assertEquals(preview.h, framed.crop.h)
        assertTrue(framed.unchanged)
        val edge = 0.02f
        assertTrue(inside(framed.roi, 0.5f, 0.5f))
        assertTrue(framed.crop.contains((edge * preview.w).toInt() + 1, preview.h / 2))
    }

    @Test
    fun sensorCropKeepsTheAspectSliceAndTheRoi() {
        val upW = 3000
        val upH = 4000
        val zoom = 2f
        val preview = CaptureFraming.previewOnSensor(upW, upH, viewW, viewH, zoom)
        // Buffer is the zoom window only: center half, sensor aspect, not yet
        // cut to the wide preview.
        val window = CaptureFraming.Px(750, 1000, 1500, 2000)
        val framed = CaptureFraming.frame(window.w, window.h, window, upW, upH, viewW, viewH, zoom, roi)
        val dieX = 1500
        val dieY = 2000
        val inBitmapX = dieX - window.x
        val inBitmapY = dieY - window.y
        assertTrue(framed.crop.contains(inBitmapX, inBitmapY))
        assertTrue(framed.crop.h < window.h)
        assertTrue(framed.crop.w >= window.w - 2)
        // The top of the zoom window is outside the wide preview.
        assertTrue(!framed.crop.contains(window.w / 2, 2))
        val nx = (dieX - preview.x).toFloat() / preview.w
        val ny = (dieY - preview.y).toFloat() / preview.h
        assertTrue(inside(framed.roi, nx, ny) || (nx in framed.roi.left..framed.roi.right && ny in framed.roi.top..framed.roi.bottom))
    }

    @Test
    fun rotatedSensorRectStillContainsItsDie() {
        val sensorW = 4000
        val sensorH = 3000
        val rect = CaptureFraming.rotateRect(1800, 1200, 400, 300, sensorW, sensorH, 90)
        val die = CaptureFraming.toUpright(2000, 1350, sensorW, sensorH, 90)
        assertTrue(rect.contains(die.first, die.second))
        val (upW, upH) = CaptureFraming.uprightSize(sensorW, sensorH, 90)
        val full = CaptureFraming.rotateRect(0, 0, sensorW, sensorH, sensorW, sensorH, 90)
        assertEquals(0, full.x)
        assertEquals(0, full.y)
        assertEquals(upW, full.w)
        assertTrue(full.h == upH || full.h == upH - 0)
        assertEquals(upH, full.h)
    }

    private fun inside(roi: NormRect, nx: Float, ny: Float): Boolean {
        return nx in roi.left..roi.right && ny in roi.top..roi.bottom
    }
}
