package com.srrtracker.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.srrtracker.detect.CaptureFraming
import com.srrtracker.detect.ColoredDiceReader
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
    val rawH: Int,
    val dicePresent: Boolean,
    val framingNote: String
)

/**
 * Rear camera, mounted face-down. Preview frames drive the motion gate.
 * When the dice settle, a full-resolution still is taken for pip counting.
 */
@OptIn(ExperimentalCamera2Interop::class)
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
    private var activeLong = 0
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
                        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
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
        activeLong = sensorLongSide(bound)
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
            val colored = if (gate.running) coloredDice(image, gate.frame) else 0
            val info = gate.onFrame(gray, now, if (gate.running) colored else null)
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
            Log.i(TAG, "taking photo manual=$manual present=${info.diceInBox} colored=$colored ${info.detail}")
            val gen = acquired
            main.post {
                if (stopped) {
                    finishCapture(gen)
                    return@post
                }
                val view = previewView
                val viewW = view?.width ?: 0
                val viewH = view?.height ?: 0
                val stream = previewUse?.resolutionInfo?.resolution
                val streamLabel = if (stream == null) "unknown" else "${stream.width}x${stream.height}"
                try {
                    analysisExecutor.execute {
                        takeStill(capture, gen, roi, viewW, viewH, streamLabel, attempt = 1)
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
        attempt: Int
    ) {
        if (stopped) {
            finishCapture(gen)
            return
        }
        busySince = System.currentTimeMillis()
        try {
            capture.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(photo: ImageProxy) {
                    var closed = false
                    try {
                        if (activeGen.get() != gen) {
                            Log.w(TAG, "late photo ignored")
                            return
                        }
                        val buffer = photo.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val upright = uprightBitmap(bytes, photo.imageInfo.rotationDegrees, viewW, viewH)
                        val rawW = upright.width
                        val rawH = upright.height
                        val framed = frameStill(upright, photo, viewW, viewH, roi)
                        val rgb = framed.bitmap.toRgbImage()
                        val dice = ColoredDiceReader.diceCount(rgb, framed.roi)
                        val present = dice == 2
                        Log.i(
                            TAG,
                            "still try $attempt ${rgb.width}x${rgb.height} raw ${rawW}x${rawH} " +
                                "preview view ${viewW}x${viewH} stream $streamLabel " +
                                "proxy ${photo.width}x${photo.height} rot=${photo.imageInfo.rotationDegrees} " +
                                "crop=${photo.cropRect} present=$present ${framed.note}"
                        )
                        // An empty table stays empty. Do not burn extra frames on it.
                        // A partial read can be a blur, so try the still again.
                        if (!present && dice > 0 && attempt < MAX_TRIES) {
                            if (!framed.bitmap.isRecycled) framed.bitmap.recycle()
                            Log.i(TAG, "saved frame has no dice, taking another")
                            closed = true
                            photo.close()
                            takeStill(capture, gen, roi, viewW, viewH, streamLabel, attempt + 1)
                            return
                        }
                        if (!finishCapture(gen)) {
                            if (!framed.bitmap.isRecycled) framed.bitmap.recycle()
                            Log.w(TAG, "late photo ignored")
                            return
                        }
                        val jpeg = fullJpeg(framed.bitmap)
                        if (!framed.bitmap.isRecycled) framed.bitmap.recycle()
                        val note = framed.note + if (present) "" else " No dice in the saved frame after $attempt tries."
                        val shot = StillCapture(
                            rgb, jpeg, framed.roi, viewW, viewH, streamLabel, rawW, rawH, present, note
                        )
                        main.post { if (!stopped) onStill(shot) }
                    } catch (t: Throwable) {
                        if (finishCapture(gen)) {
                            Log.e(TAG, "photo decode failed", t)
                            gate.recheckSoon()
                            main.post { if (!stopped) onError(t.message ?: "Could not read the photo.") }
                        }
                    } finally {
                        if (!closed) {
                            try {
                                photo.close()
                            } catch (_: Throwable) {
                            }
                        }
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

    private class Framed(val bitmap: Bitmap, val roi: NormRect?, val note: String)

    /**
     * Keep the preview's field of view. A JPEG that already matches that view
     * is not cropped again. A full-sensor JPEG is center-cropped for zoom and
     * aspect, so a die in the preview stays in the still.
     */
    private fun frameStill(
        bitmap: Bitmap,
        photo: ImageProxy,
        viewW: Int,
        viewH: Int,
        roi: NormRect?
    ): Framed {
        val box = roi ?: NormRect(0f, 0f, 1f, 1f)
        val degrees = rotationUsed(bitmap, photo, viewW, viewH)
        val sensorW = photo.width
        val sensorH = photo.height
        val (upW, upH) = CaptureFraming.uprightSize(sensorW, sensorH, degrees)
        val rect = photo.cropRect
        val rotated = CaptureFraming.rotateRect(rect.left, rect.top, rect.width(), rect.height(), sensorW, sensorH, degrees)
        val bitmapIsFull = bitmap.width >= upW - 2 && bitmap.height >= upH - 2
        val longSide = max(bitmap.width, bitmap.height)
        val fullSensor = activeLong > 0 && longSide >= (activeLong * 0.92f).toInt()
        val zoomForMath = if (bitmapIsFull && fullSensor) zoomRatio else 1f
        val sensorCrop = if (bitmapIsFull) {
            CaptureFraming.Px(0, 0, bitmap.width, bitmap.height)
        } else {
            rotated
        }
        val uprightW = if (bitmapIsFull) bitmap.width else upW
        val uprightH = if (bitmapIsFull) bitmap.height else upH
        val framed = CaptureFraming.frame(
            bitmapW = bitmap.width,
            bitmapH = bitmap.height,
            sensorCrop = if (bitmapIsFull) sensorCrop else CaptureFraming.Px(0, 0, bitmap.width, bitmap.height),
            uprightW = if (bitmapIsFull) uprightW else bitmap.width,
            uprightH = if (bitmapIsFull) uprightH else bitmap.height,
            viewW = viewW.coerceAtLeast(1),
            viewH = viewH.coerceAtLeast(1),
            zoom = zoomForMath,
            roi = box
        )
        // When the JPEG is already smaller than the sensor, treat it as the
        // preview buffer: only an aspect trim, never a second zoom.
        val use = if (!bitmapIsFull || !fullSensor) {
            CaptureFraming.frame(
                bitmap.width,
                bitmap.height,
                CaptureFraming.Px(0, 0, bitmap.width, bitmap.height),
                bitmap.width,
                bitmap.height,
                viewW.coerceAtLeast(1),
                viewH.coerceAtLeast(1),
                zoom = 1f,
                roi = box
            )
        } else {
            framed
        }
        val cropped = cropBitmap(bitmap, use.crop)
        val note = if (use.unchanged) {
            "Framing kept the full ${bitmap.width}x${bitmap.height} still (zoom math $zoomForMath, sensor long $activeLong)."
        } else {
            "Framing center crop ${use.crop.w}x${use.crop.h} from ${bitmap.width}x${bitmap.height} at zoom $zoomForMath."
        }
        return Framed(cropped, use.roi, note)
    }

    private fun cropBitmap(bitmap: Bitmap, crop: CaptureFraming.Px): Bitmap {
        val x = crop.x.coerceIn(0, bitmap.width - 1)
        val y = crop.y.coerceIn(0, bitmap.height - 1)
        val w = crop.w.coerceIn(1, bitmap.width - x)
        val h = crop.h.coerceIn(1, bitmap.height - y)
        if (w >= bitmap.width - 2 && h >= bitmap.height - 2) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    /**
     * Colored dice inside the upright preview box. The teal rail and grey cloth
     * are the wrong hue, so an empty table counts as zero and does not capture.
     * A failure counts as zero too: no dice, no shutter.
     */
    private fun coloredDice(image: ImageProxy, roi: NormRect): Int {
        val rgb = uprightBox(image, roi) ?: return 0
        return try {
            ColoredDiceReader.diceCount(rgb)
        } catch (t: Throwable) {
            Log.w(TAG, "preview dice count failed", t)
            0
        }
    }

    private fun uprightBox(image: ImageProxy, roi: NormRect): RgbImage? {
        if (image.planes.size < 3) return null
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        val yPos = yBuf.position()
        val uPos = uBuf.position()
        val vPos = vBuf.position()
        val rot = image.imageInfo.rotationDegrees
        val srcW = image.width
        val srcH = image.height
        val upW = if (rot == 90 || rot == 270) srcH else srcW
        val upH = if (rot == 90 || rot == 270) srcW else srcH
        if (upW < 2 || upH < 2) return null
        val x0 = (roi.left * upW).toInt().coerceIn(0, upW - 1)
        val y0 = (roi.top * upH).toInt().coerceIn(0, upH - 1)
        val x1 = (roi.right * upW).toInt().coerceIn(x0 + 1, upW)
        val y1 = (roi.bottom * upH).toInt().coerceIn(y0 + 1, upH)
        val bw = x1 - x0
        val bh = y1 - y0
        // Keep dice that are only ~15px in the wide view. Sampling this box
        // down to 480px used to skip them, so the shutter saw zero dice.
        val step = max(1, max(bw, bh) / 1280)
        val ow = max(1, bw / step)
        val oh = max(1, bh / step)
        val pixels = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val uy = (y0 + oy * step).coerceIn(0, upH - 1)
            val row = oy * ow
            for (ox in 0 until ow) {
                val ux = (x0 + ox * step).coerceIn(0, upW - 1)
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
                val yIndex = yPos + ssy * yPlane.rowStride + ssx * yPlane.pixelStride.coerceAtLeast(1)
                val cx = ssx / 2
                val cy = ssy / 2
                val uIndex = uPos + cy * uPlane.rowStride + cx * uPlane.pixelStride.coerceAtLeast(1)
                val vIndex = vPos + cy * vPlane.rowStride + cx * vPlane.pixelStride.coerceAtLeast(1)
                val y = if (yIndex in 0 until yBuf.limit()) yBuf.get(yIndex).toInt() and 0xFF else 0
                val u = if (uIndex in 0 until uBuf.limit()) uBuf.get(uIndex).toInt() and 0xFF else 128
                val v = if (vIndex in 0 until vBuf.limit()) vBuf.get(vIndex).toInt() and 0xFF else 128
                val c = y - 16
                val d = u - 128
                val e = v - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                pixels[row + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RgbImage(ow, oh, pixels)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun sensorLongSide(bound: Camera): Int {
        return try {
            val rect = Camera2CameraInfo.from(bound.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (rect == null) 0 else max(rect.width(), rect.height())
        } catch (t: Throwable) {
            Log.w(TAG, "sensor size unavailable", t)
            0
        }
    }

    /**
     * EXIF rotation first. A proxy rotation is applied only when it makes the
     * bitmap's aspect closer to the preview. That avoids turning an
     * already-upright JPEG on its side.
     */
    private fun uprightBitmap(jpeg: ByteArray, proxyDegrees: Int, viewW: Int, viewH: Int): Bitmap {
        val bitmap = decodeUpright(jpeg)
        val exif = exifDegrees(jpeg)
        if (exif != 0 || proxyDegrees == 0 || viewW < 2 || viewH < 2) return bitmap
        val swap = proxyDegrees == 90 || proxyDegrees == 270
        if (!swap && proxyDegrees != 180) return bitmap
        val viewA = viewW.toFloat() / viewH
        val now = bitmap.width.toFloat() / bitmap.height
        val turned = if (swap) bitmap.height.toFloat() / bitmap.width else now
        val nowErr = abs(now - viewA) / viewA
        val turnErr = abs(turned - viewA) / viewA
        if (turnErr + 0.02f >= nowErr) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(proxyDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun rotationUsed(bitmap: Bitmap, photo: ImageProxy, viewW: Int, viewH: Int): Int {
        val proxy = photo.imageInfo.rotationDegrees
        if (viewW < 2 || viewH < 2) return proxy
        val viewA = viewW.toFloat() / viewH
        val now = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val nowErr = abs(now - viewA) / viewA
        val swap = proxy == 90 || proxy == 270
        if (!swap) return if (nowErr < 0.08f) 0 else proxy
        val turned = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
        val turnErr = abs(turned - viewA) / viewA
        return if (turnErr + 0.02f < nowErr) proxy else 0
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
        private const val CAPTURE_TIMEOUT_MS = 20_000L
        private const val MAX_EDGE = 4096
        private const val MAX_TRIES = 3
    }
}
