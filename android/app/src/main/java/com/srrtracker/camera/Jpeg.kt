package com.srrtracker.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.srrtracker.detect.RgbImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

fun decodeUpright(jpeg: ByteArray): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        ?: error("Could not read the photo")
    val exif = ExifInterface(ByteArrayInputStream(jpeg))
    val degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

fun Bitmap.toRgbImage(): RgbImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return RgbImage(width, height, pixels)
}

fun downscaledJpeg(bitmap: Bitmap, maxEdge: Int = 1280, quality: Int = 74): ByteArray {
    val long = max(bitmap.width, bitmap.height)
    val scaled = if (long <= maxEdge) {
        bitmap
    } else {
        val s = maxEdge.toFloat() / long
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).roundToInt().coerceAtLeast(1), (bitmap.height * s).roundToInt().coerceAtLeast(1), true)
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== bitmap) scaled.recycle()
    return out.toByteArray()
}
