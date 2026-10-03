package com.srrtracker.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    @Volatile private var stopped = false

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
        try {
            if (stopped) return
            val gray = toMotionGray(image)
            val info = gate.onFrame(gray, System.currentTimeMillis())
            main.post { if (!stopped) onFrame(info) }
            if (info.shouldCapture && busy.compareAndSet(false, true)) {
                val roi = info.roi
                val capture = imageCapture
                if (capture == null) {
                    busy.set(false)
                    return
                }
                capture.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(photo: ImageProxy) {
                        try {
                            val buffer = photo.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val bitmap = decodeUpright(bytes)
                            val rgb = bitmap.toRgbImage()
                            val jpeg = downscaledJpeg(bitmap)
                            bitmap.recycle()
                            main.post {
                                if (!stopped) onStill(rgb, jpeg, roi)
                            }
                        } catch (t: Throwable) {
                            gate.recheckSoon()
                            main.post { if (!stopped) onError(t.message ?: "Could not read the photo") }
                        } finally {
                            photo.close()
                            busy.set(false)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        busy.set(false)
                        gate.recheckSoon()
                        main.post { if (!stopped) onError(exception.message ?: "Photo failed") }
                    }
                })
            }
        } catch (_: Throwable) {
        } finally {
            image.close()
        }
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
}
