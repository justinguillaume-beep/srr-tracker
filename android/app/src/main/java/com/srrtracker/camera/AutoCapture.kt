package com.srrtracker.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
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
import com.srrtracker.detect.MotionGate
import com.srrtracker.detect.NormRect
import com.srrtracker.detect.RgbImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Rear camera, mounted face-down. Preview frames drive the motion gate.
 * When the dice settle, a high-resolution still is taken for pip counting.
 */
class AutoCapture(
    private val context: Context,
    private val onFrame: (MotionGate.FrameInfo) -> Unit,
    private val onStill: (RgbImage, ByteArray, NormRect?) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) {
    val gate = MotionGate()
    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val busy = AtomicBoolean(false)
    private val force = AtomicBoolean(false)
    private val captureGen = AtomicInteger(0)
    private val activeGen = AtomicInteger(0)
    private var busySince = 0L
    @Volatile private var stopped = false

    /** Take a photo on the next frame even if the dice have not settled. */
    fun requestCapture() {
        force.set(true)
        Log.i(TAG, "manual capture requested")
    }

    fun start(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        stopped = false
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

    private fun bind(cameraProvider: ProcessCameraProvider, previewView: PreviewView, owner: LifecycleOwner) {
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

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
                    .setResolutionFilter(HighestPractical())
                    .build()
            )
            .build()
        imageCapture = capture

        val width = previewView.width.coerceAtLeast(1)
        val height = previewView.height.coerceAtLeast(1)
        val viewPort = ViewPort.Builder(Rational(width, height), rotation)
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
        cameraProvider.bindToLifecycle(owner, selector, group)
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
                            val bitmap = decodeUpright(bytes)
                            val rgb = bitmap.toRgbImage()
                            val jpeg = downscaledJpeg(bitmap)
                            bitmap.recycle()
                            Log.i(TAG, "photo decoded ${rgb.width}x${rgb.height}")
                            main.post {
                                if (!stopped) onStill(rgb, jpeg, roi)
                            }
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

    private class HighestPractical : ResolutionFilter {
        override fun filter(supportedSizes: List<Size>, rotationDegrees: Int): List<Size> {
            val practical = supportedSizes.filter {
                max(it.width, it.height) <= 3840 && min(it.width, it.height) >= 720
            }
            val pool = practical.ifEmpty { supportedSizes }
            val best = pool.maxBy { it.width.toLong() * it.height }
            return listOf(best)
        }
    }

    companion object {
        private const val TAG = "SrrTracker"
        private const val CAPTURE_TIMEOUT_MS = 4_000L
    }
}
