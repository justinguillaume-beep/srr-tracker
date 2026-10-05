package com.srrtracker.detect

import kotlin.math.max
import kotlin.math.min

/**
 * Maps the live preview onto a captured still.
 *
 * The preview is a centered zoom of the upright sensor, then a center crop to
 * the view's aspect (the same FILL_CENTER crop the preview uses). A captured
 * buffer may already be that preview, or it may still be the full sensor.
 * The saved still is the overlap. A buffer that is already the preview is not
 * zoomed a second time, which is what pushed the dice out of the last build.
 */
object CaptureFraming {
    data class Px(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int get() = x + w
        val bottom: Int get() = y + h
        fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < right && py < bottom
    }

    data class Frame(
        /** Crop in decoded-bitmap pixels. This rectangle is the saved still. */
        val crop: Px,
        /** The on-screen box, normalized onto the saved still. */
        val roi: NormRect,
        /** The bitmap was already the preview, so it was not shrunk. */
        val unchanged: Boolean
    )

    fun uprightSize(sensorW: Int, sensorH: Int, degrees: Int): Pair<Int, Int> {
        return if (degrees == 90 || degrees == 270) sensorH to sensorW else sensorW to sensorH
    }

    /** Sensor pixel to upright pixel. 90° matches a clockwise quarter turn. */
    fun toUpright(sx: Int, sy: Int, sensorW: Int, sensorH: Int, degrees: Int): Pair<Int, Int> {
        return when (degrees) {
            90 -> (sensorH - 1 - sy) to sx
            180 -> (sensorW - 1 - sx) to (sensorH - 1 - sy)
            270 -> sy to (sensorW - 1 - sx)
            else -> sx to sy
        }
    }

    /** Axis-aligned bound of a sensor rectangle after the same rotation. */
    fun rotateRect(x: Int, y: Int, w: Int, h: Int, sensorW: Int, sensorH: Int, degrees: Int): Px {
        if (w <= 0 || h <= 0) {
            val (uw, uh) = uprightSize(sensorW, sensorH, degrees)
            return Px(0, 0, uw, uh)
        }
        val corners = arrayOf(
            toUpright(x, y, sensorW, sensorH, degrees),
            toUpright(x + w - 1, y, sensorW, sensorH, degrees),
            toUpright(x, y + h - 1, sensorW, sensorH, degrees),
            toUpright((x + w - 1).coerceAtLeast(x), (y + h - 1).coerceAtLeast(y), sensorW, sensorH, degrees)
        )
        val minX = corners.minOf { it.first }
        val minY = corners.minOf { it.second }
        val maxX = corners.maxOf { it.first }
        val maxY = corners.maxOf { it.second }
        return Px(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /**
     * Preview field of view in upright full-sensor pixels.
     * [zoom] of 1 shows the whole sensor. A higher zoom keeps the center.
     */
    fun previewOnSensor(uprightW: Int, uprightH: Int, viewW: Int, viewH: Int, zoom: Float): Px {
        val z = if (zoom < 1f) 1f else zoom
        val zw = (uprightW / z).coerceIn(1f, uprightW.toFloat())
        val zh = (uprightH / z).coerceIn(1f, uprightH.toFloat())
        val viewAspect = viewW.toFloat() / viewH.coerceAtLeast(1)
        val windowAspect = zw / zh
        val cw: Float
        val ch: Float
        if (windowAspect > viewAspect) {
            ch = zh
            cw = (zh * viewAspect).coerceAtMost(zw)
        } else {
            cw = zw
            ch = (zw / viewAspect).coerceAtMost(zh)
        }
        val x = ((uprightW - cw) / 2f).coerceAtLeast(0f)
        val y = ((uprightH - ch) / 2f).coerceAtLeast(0f)
        return Px(
            x.toInt().coerceIn(0, uprightW - 1),
            y.toInt().coerceIn(0, uprightH - 1),
            cw.toInt().coerceIn(1, uprightW),
            ch.toInt().coerceIn(1, uprightH)
        )
    }

    /**
     * [sensorCrop] is the decoded bitmap's footprint on the upright sensor.
     * The bitmap's pixel (0, 0) is [sensorCrop]'s top-left. When the bitmap
     * is already the preview, [sensorCrop] is the preview and the crop is the
     * whole bitmap.
     */
    fun frame(
        bitmapW: Int,
        bitmapH: Int,
        sensorCrop: Px,
        uprightW: Int,
        uprightH: Int,
        viewW: Int,
        viewH: Int,
        zoom: Float,
        roi: NormRect
    ): Frame {
        val preview = previewOnSensor(uprightW, uprightH, viewW, viewH, zoom)
        val left = max(preview.x, sensorCrop.x)
        val top = max(preview.y, sensorCrop.y)
        val right = min(preview.right, sensorCrop.right)
        val bottom = min(preview.bottom, sensorCrop.bottom)
        val uprightKeep = if (right - left >= 8 && bottom - top >= 8) {
            Px(left, top, right - left, bottom - top)
        } else {
            sensorCrop
        }
        val bx = (uprightKeep.x - sensorCrop.x).coerceIn(0, (bitmapW - 1).coerceAtLeast(0))
        val by = (uprightKeep.y - sensorCrop.y).coerceIn(0, (bitmapH - 1).coerceAtLeast(0))
        val bw = uprightKeep.w.coerceIn(1, (bitmapW - bx).coerceAtLeast(1))
        val bh = uprightKeep.h.coerceIn(1, (bitmapH - by).coerceAtLeast(1))
        val crop = Px(bx, by, bw, bh)
        val unchanged = crop.w >= bitmapW - 2 && crop.h >= bitmapH - 2
        return Frame(crop, mapRoi(roi, preview, uprightKeep), unchanged)
    }

    fun mapRoi(roi: NormRect, preview: Px, kept: Px): NormRect {
        fun nx(v: Float): Float = ((preview.x + v * preview.w - kept.x) / kept.w).coerceIn(0f, 1f)
        fun ny(v: Float): Float = ((preview.y + v * preview.h - kept.y) / kept.h).coerceIn(0f, 1f)
        val l = nx(roi.left)
        val t = ny(roi.top)
        val r = nx(roi.right)
        val b = ny(roi.bottom)
        val left = min(l, r)
        val top = min(t, b)
        return NormRect(
            left,
            top,
            max(l, r).coerceAtLeast(left + 0.02f).coerceAtMost(1f),
            max(t, b).coerceAtLeast(top + 0.02f).coerceAtMost(1f)
        )
    }
}
