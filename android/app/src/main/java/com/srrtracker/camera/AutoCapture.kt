package com.srrtracker.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.srrtracker.detect.MotionGate
import com.srrtracker.detect.NormRect
import com.srrtracker.detect.RgbImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One captured still plus how it lines up with the live preview.
 * [image] is the upright full-resolution photo the detector reads. It is not
 * a downscale of the sensor frame.
 */
class StillCapture(
    val image: RgbImage,
    val jpeg: ByteArray,
    val roi: NormRect?,
    val previewViewW: Int,
    val previewViewH: Int,
    val previewStream: String,
    val rawW: Int,
    val rawH: Int
)

/**
 * Rear camera, mounted face-down. Preview frames drive the motion gate.
 * When the dice settle, a full-resolution still is taken for pip counting.
 */
@OptIn(TransformExperimental::class)
class AutoCapture(
    private val context: Context,
    private val onFrame: (MotionGate.FrameInfo) -> Unit,
    private val onStill: (StillCapture) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    private val onZoomRange: (Float, Float) -> Unit = { _, _ -> }
) {
    val gate = MotionGate()
    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var previewUse: Preview? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private val busy = AtomicBoolean(false)
    private val force = AtomicBoolean(false)
    private val captureGen = AtomicInteger(0)
    private val activeGen = AtomicInteger(0)
    private var busySince = 0L
    @Volatile private var stopped = false

    /** Zoom ratio applied on the next bind and whenever the slider moves. */
    var zoomRatio: Float = 1f
        set(value) {
            field = value
            applyZoom()
        }

    /** Take a photo on the next frame even if the dice have not settled. */
    fun requestCapture() {
        force.set(true)
        Log.i(TAG, "manual capture requested")
    }

    fun start(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        stopped = false
        this.previewView = previewView
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                bind(cameraProvider, previewView, lifecycleOwner)
                main.post { onReady() }
            } catch (t: Throwable) {
                main.post { onError(t.message ?: "Camera did not start") }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopped = true
        busy.set(false)
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        analysisExecutor.shutdown()
    }

    fun recheckSoon() {
        gate.recheckSoon()
    }

    private fun applyZoom() {
        val cam = camera ?: return
        val info = cam.cameraInfo.zoomState.value
        val minZ = info?.minZoomRatio ?: 1f
        val maxZ = info?.maxZoomRatio ?: zoomRatio.coerceAtLeast(1f)
        val z = zoomRatio.coerceIn(minZ, maxZ.coerceAtLeast(minZ))
        cam.cameraControl.setZoomRatio(z)
    }

    private fun bind(cameraProvider: ProcessCameraProvider, previewView: PreviewView, owner: LifecycleOwner) {
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        previewUse = preview

        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

        val capture = ImageCapture.Builder()
            .setTargetRotation(rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionFilter(HighestSensor())
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
        imageCapture = capture

        val width = previewView.width.coerceAtLeast(1)
        val height = previewView.height.coerceAtLeast(1)
        val viewPort = previewView.viewPort ?: ViewPort.Builder(Rational(width, height), rotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .addUseCase(capture)
            .build()

        cameraProvider.unbindAll()
        val selector = when {
            cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> error("No camera on this phone")
        }
        val bound = cameraProvider.bindToLifecycle(owner, selector, group)
        camera = bound
        applyZoom()
        bound.cameraInfo.zoomState.observe(owner) { zs ->
            onZoomRange(zs.minZoomRatio, zs.maxZoomRatio)
        }
        Log.i(TAG, "camera bound preview view ${width}x${height}")
    }

    private fun analyze(image: ImageProxy) {
        var acquired = 0
        try {
            if (stopped) return
            val now = System.currentTimeMillis()
            if (busy.get() && activeGen.get() != 0 && now - busySince > CAPTURE_TIMEOUT_MS) {
                val gen = activeGen.get()
                if (gen != 0 && activeGen.compareAndSet(gen, 0)) {
                    busy.set(false)
                    gate.recheckSoon()
                    Log.e(TAG, "capture timed out")
                    main.post { if (!stopped) onError("The photo took too long.") }
                }
            }
            val gray = toMotionGray(image)
            val info = gate.onFrame(gray, now)
            val manual = force.get()
            main.post { if (!stopped) onFrame(info) }
            if (!info.shouldCapture && !manual) return
            if (!busy.compareAndSet(false, true)) {
                Log.i(TAG, "capture waiting, camera still busy manual=$manual ${info.detail}")
                return
            }
            acquired = captureGen.incrementAndGet()
            activeGen.set(acquired)
            busySince = now
            force.set(false)
            if (manual && !info.shouldCapture) gate.holdAfterManual()
            val roi = info.roi
            val capture = imageCapture
            if (capture == null) {
                finishCapture(acquired)
                gate.recheckSoon()
                Log.e(TAG, "capture requested but the camera is not ready")
                main.post { if (!stopped) onError("Camera is not ready to take a photo.") }
                return
            }
            Log.i(TAG, "taking photo manual=$manual present=${info.diceInBox} ${info.detail}")
            val gen = acquired
            main.post {
                if (stopped) {
                    finishCapture(gen)
                    return@post
                }
                val view = previewView
                val viewW = view?.width ?: 0
                val viewH = view?.height ?: 0
                val viewTransform = try {
                    view?.outputTransform
                } catch (t: Throwable) {
                    Log.w(TAG, "preview transform unavailable", t)
                    null
                }
                val stream = previewUse?.resolutionInfo?.resolution
                val streamLabel = if (stream == null) "unknown" else "${stream.width}x${stream.height}"
                try {
                    analysisExecutor.execute {
                        takeStill(capture, gen, roi, viewW, viewH, streamLabel, viewTransform)
                    }
                } catch (t: Throwable) {
                    finishCapture(gen)
                    gate.recheckSoon()
                    Log.e(TAG, "takePicture schedule failed", t)
                    main.post { if (!stopped) onError(t.message ?: "Photo failed.") }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame analysis failed", t)
            if (acquired != 0) {
                finishCapture(acquired)
                gate.recheckSoon()
                main.post { if (!stopped) onError(t.message ?: "Camera frame failed.") }
            }
        } finally {
            image.close()
        }
    }

    private fun takeStill(
        capture: ImageCapture,
        gen: Int,
        roi: NormRect?,
        viewW: Int,
        viewH: Int,
        streamLabel: String,
        viewTransform: OutputTransform?
    ) {
        if (stopped) {
            finishCapture(gen)
            return
        }
        try {
            capture.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(photo: ImageProxy) {
                    if (!finishCapture(gen)) {
                        photo.close()
                        Log.w(TAG, "late photo ignored")
                        return
                    }
                    try {
                        val buffer = photo.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val upright = uprightBitmap(bytes, photo.imageInfo.rotationDegrees)
                        val rawW = upright.width
                        val rawH = upright.height
                        val framed = frameStill(upright, photo, viewW, viewH, viewTransform, roi)
                        val rgb = framed.bitmap.toRgbImage()
                        val jpeg = fullJpeg(framed.bitmap)
                        if (!framed.bitmap.isRecycled) framed.bitmap.recycle()
                        Log.i(
                            TAG,
                            "still ${rgb.width}x${rgb.height} raw ${rawW}x${rawH} " +
                                "preview view ${viewW}x${viewH} stream $streamLabel " +
                                "proxy ${photo.width}x${photo.height} rot=${photo.imageInfo.rotationDegrees} " +
                                "crop=${photo.cropRect} roi=${framed.roi} mapped=${framed.mapped}"
                        )
                        val shot = StillCapture(rgb, jpeg, framed.roi, viewW, viewH, streamLabel, rawW, rawH)
                        main.post { if (!stopped) onStill(shot) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "photo decode failed", t)
                        gate.recheckSoon()
                        main.post { if (!stopped) onError(t.message ?: "Could not read the photo.") }
                    } finally {
                        photo.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (!finishCapture(gen)) return
                    Log.e(TAG, "takePicture failed code=${exception.imageCaptureError}", exception)
                    gate.recheckSoon()
                    main.post { if (!stopped) onError(exception.message ?: "Photo failed.") }
                }
            })
        } catch (t: Throwable) {
            finishCapture(gen)
            gate.recheckSoon()
            Log.e(TAG, "takePicture threw", t)
            main.post { if (!stopped) onError(t.message ?: "Photo failed.") }
        }
    }

    private class Framed(val bitmap: Bitmap, val roi: NormRect?, val mapped: Boolean)

    /**
     * Crop the upright still to the preview's field of view when the still is
     * wider than the live picture, and map the on-screen box into that crop.
     */
    private fun frameStill(
        bitmap: Bitmap,
        photo: ImageProxy,
        viewW: Int,
        viewH: Int,
        viewTransform: OutputTransform?,
        roi: NormRect?
    ): Framed {
        if (viewTransform != null && viewW > 1 && viewH > 1 && roi != null) {
            try {
                val factory = ImageProxyTransformFactory()
                factory.setUsingCropRect(true)
                factory.setUsingRotationDegrees(true)
                val imageTransform = factory.getOutputTransform(photo)
                val transform = CoordinateTransform(viewTransform, imageTransform)
                val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat()).also { transform.mapRect(it) }.normalized()
                val roiRect = RectF(
                    roi.left * viewW,
                    roi.top * viewH,
                    roi.right * viewW,
                    roi.bottom * viewH
                ).also { transform.mapRect(it) }.normalized()
                val viewAspect = viewW.toFloat() / viewH
                val mapAspect = viewRect.width() / viewRect.height().coerceAtLeast(1f)
                val aspectOk = abs(viewAspect - mapAspect) / viewAspect < 0.35f
                val usable = aspectOk && viewRect.width() > 32f && viewRect.height() > 32f &&
                    viewRect.left < bitmap.width && viewRect.top < bitmap.height
                if (usable) {
                    val covers = viewRect.width() >= bitmap.width * 0.92f && viewRect.height() >= bitmap.height * 0.92f
                    val cropped = if (covers) bitmap else cropBitmap(bitmap, viewRect)
                    val originX = if (covers) 0f else viewRect.left.coerceAtLeast(0f)
                    val originY = if (covers) 0f else viewRect.top.coerceAtLeast(0f)
                    val mapped = NormRect(
                        left = ((roiRect.left - originX) / cropped.width).coerceIn(0f, 0.98f),
                        top = ((roiRect.top - originY) / cropped.height).coerceIn(0f, 0.98f),
                        right = ((roiRect.right - originX) / cropped.width).coerceIn(0.02f, 1f),
                        bottom = ((roiRect.bottom - originY) / cropped.height).coerceIn(0.02f, 1f)
                    ).sorted()
                    return Framed(cropped, mapped, true)
                }
                Log.w(TAG, "preview map missed the still view=$viewRect bitmap=${bitmap.width}x${bitmap.height}")
            } catch (t: Throwable) {
                Log.w(TAG, "preview map failed", t)
            }
        }
        val cropped = centerCropToPreview(bitmap, viewW, viewH)
        return Framed(cropped, roi, false)
    }

    private fun RectF.normalized(): RectF {
        val l = min(left, right)
        val t = min(top, bottom)
        val r = max(left, right)
        val b = max(top, bottom)
        return RectF(l, t, r, b)
    }

    private fun NormRect.sorted(): NormRect {
        val l = min(left, right)
        val r = max(left, right)
        val t = min(top, bottom)
        val b = max(top, bottom)
        return if (r - l < 0.02f || b - t < 0.02f) this else NormRect(l, t, r, b)
    }

    private fun cropBitmap(bitmap: Bitmap, rect: RectF): Bitmap {
        val l = rect.left.coerceIn(0f, (bitmap.width - 1).toFloat())
        val t = rect.top.coerceIn(0f, (bitmap.height - 1).toFloat())
        val r = rect.right.coerceIn(l + 1f, bitmap.width.toFloat())
        val b = rect.bottom.coerceIn(t + 1f, bitmap.height.toFloat())
        val x = l.toInt()
        val y = t.toInt()
        val w = (r - l).toInt().coerceIn(1, bitmap.width - x)
        val h = (b - t).toInt().coerceIn(1, bitmap.height - y)
        if (w >= bitmap.width - 2 && h >= bitmap.height - 2) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    private fun centerCropToPreview(bitmap: Bitmap, viewW: Int, viewH: Int): Bitmap {
        if (viewW < 2 || viewH < 2) return bitmap
        val target = viewW.toFloat() / viewH
        val current = bitmap.width.toFloat() / bitmap.height
        if (abs(target - current) / target < 0.04f) return bitmap
        val w: Int
        val h: Int
        if (current > target) {
            h = bitmap.height
            w = (bitmap.height * target).toInt().coerceIn(1, bitmap.width)
        } else {
            w = bitmap.width
            h = (bitmap.width / target).toInt().coerceIn(1, bitmap.height)
        }
        val x = ((bitmap.width - w) / 2).coerceAtLeast(0)
        val y = ((bitmap.height - h) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    /** EXIF rotation first. If the file has none, use the capture's rotation. */
    private fun uprightBitmap(jpeg: ByteArray, proxyDegrees: Int): Bitmap {
        val bitmap = decodeUpright(jpeg)
        val exif = exifDegrees(jpeg)
        if (exif != 0 || proxyDegrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(proxyDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun exifDegrees(jpeg: ByteArray): Int {
        val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(jpeg))
        return when (exif.getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        )) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun finishCapture(gen: Int): Boolean {
        if (!activeGen.compareAndSet(gen, 0)) return false
        busy.set(false)
        return true
    }

    private fun toMotionGray(image: ImageProxy): IntArray {
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate()
        val offset = buf.position()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rot = image.imageInfo.rotationDegrees
        val srcW = image.width
        val srcH = image.height
        val upW = if (rot == 90 || rot == 270) srcH else srcW
        val upH = if (rot == 90 || rot == 270) srcW else srcH
        val out = IntArray(MotionGate.W * MotionGate.H)
        for (oy in 0 until MotionGate.H) {
            val uy = (oy * upH / MotionGate.H).coerceIn(0, upH - 1)
            val rowOut = oy * MotionGate.W
            for (ox in 0 until MotionGate.W) {
                val ux = (ox * upW / MotionGate.W).coerceIn(0, upW - 1)
                val sx: Int
                val sy: Int
                when (rot) {
                    90 -> {
                        sx = uy
                        sy = upW - 1 - ux
                    }
                    180 -> {
                        sx = upW - 1 - ux
                        sy = upH - 1 - uy
                    }
                    270 -> {
                        sx = srcW - 1 - uy
                        sy = ux
                    }
                    else -> {
                        sx = ux
                        sy = uy
                    }
                }
                val ssx = sx.coerceIn(0, srcW - 1)
                val ssy = sy.coerceIn(0, srcH - 1)
                val index = offset + ssy * rowStride + ssx * pixelStride
                out[rowOut + ox] = if (index in 0 until buf.limit()) buf.get(index).toInt() and 0xFF else 0
            }
        }
        return out
    }

    private class HighestSensor : ResolutionFilter {
        override fun filter(supportedSizes: List<Size>, rotationDegrees: Int): List<Size> {
            val skipped = supportedSizes.filter { max(it.width, it.height) > MAX_EDGE }
            if (skipped.isNotEmpty()) {
                Log.i(TAG, "skipped larger than $MAX_EDGE: ${skipped.joinToString { "${it.width}x${it.height}" }}")
            }
            val practical = supportedSizes.filter {
                max(it.width, it.height) <= MAX_EDGE && min(it.width, it.height) >= 720
            }
            val pool = practical.ifEmpty { supportedSizes }
            val best = pool.maxBy { it.width.toLong() * it.height }
            Log.i(
                TAG,
                "capture sizes ${supportedSizes.joinToString { "${it.width}x${it.height}" }} chosen ${best.width}x${best.height}"
            )
            return listOf(best)
        }
    }

    companion object {
        private const val TAG = "SrrTracker"
        private const val CAPTURE_TIMEOUT_MS = 8_000L
        private const val MAX_EDGE = 4096
    }
}
