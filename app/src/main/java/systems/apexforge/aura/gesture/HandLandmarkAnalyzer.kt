package systems.apexforge.aura.gesture

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX RGBA frame -> reusable ARGB bitmap -> MediaPipe MPImage.
 *
 * The Bitmap is allocated once per stream and reused synchronously. Rotation is
 * passed as metadata and selfie mirroring is done on normalized landmark X
 * values, avoiding per-frame Matrix transforms and secondary Bitmap copies.
 */
class HandLandmarkAnalyzer(
    context: android.content.Context,
    private val onHands: (List<HandFrame>, Long) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private var lastTimestampMs = 0L
    private var landmarker: HandLandmarker? = null
    private var activeDelegate: Delegate? = null
    private var cachedRotation = Int.MIN_VALUE
    private var cachedProcessingOptions: ImageProcessingOptions? = null
    private var reusableBitmap: Bitmap? = null

    private fun createLandmarker(delegate: Delegate): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.60f)
            .setMinHandPresenceConfidence(0.60f)
            .setMinTrackingConfidence(0.60f)
            .build()
        return HandLandmarker.createFromOptions(appContext, options)
    }

    private fun getOrCreateLandmarker(): HandLandmarker {
        landmarker?.let { return it }
        return try {
            createLandmarker(Delegate.GPU).also {
                landmarker = it
                activeDelegate = Delegate.GPU
                Log.i(TAG, "Hand Landmarker initialized with GPU delegate")
            }
        } catch (gpuFailure: Exception) {
            Log.w(TAG, "GPU delegate unavailable; falling back to CPU", gpuFailure)
            createLandmarker(Delegate.CPU).also {
                landmarker = it
                activeDelegate = Delegate.CPU
                Log.i(TAG, "Hand Landmarker initialized with CPU delegate")
            }
        }
    }

    private fun processingOptions(rotationDegrees: Int): ImageProcessingOptions {
        if (cachedProcessingOptions == null || cachedRotation != rotationDegrees) {
            cachedProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()
            cachedRotation = rotationDegrees
        }
        return cachedProcessingOptions!!
    }

    private fun getOrCreateBitmap(width: Int, height: Int): Bitmap {
        reusableBitmap?.let { existing ->
            if (existing.width == width && existing.height == height && !existing.isRecycled) {
                return existing
            }
            existing.recycle()
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            reusableBitmap = it
        }
    }

    @Synchronized
    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }

        var mpImage: MPImage? = null
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val plane = imageProxy.planes.firstOrNull()
                ?: throw IllegalStateException("CameraX returned no RGBA image plane")
            val bitmap = getOrCreateBitmap(imageProxy.width, imageProxy.height)
            val pixels = plane.buffer
            pixels.rewind()
            // CameraX RGBA_8888 is a single packed plane. Copy into the same
            // bitmap each frame; no Bitmap allocation or full-frame Matrix is
            // required after the first frame.
            bitmap.copyPixelsFromBuffer(pixels)
            mpImage = BitmapImageBuilder(bitmap).build()

            val timestamp = (imageProxy.imageInfo.timestamp / 1_000_000L)
                .coerceAtLeast(lastTimestampMs + 1L)
            lastTimestampMs = timestamp

            val options = processingOptions(imageProxy.imageInfo.rotationDegrees)
            var detector = getOrCreateLandmarker()
            val result = try {
                detector.detectForVideo(mpImage, options, timestamp)
            } catch (gpuFailure: RuntimeException) {
                if (activeDelegate != Delegate.GPU) throw gpuFailure
                Log.w(TAG, "GPU inference failed; switching to CPU", gpuFailure)
                runCatching { detector.close() }
                landmarker = createLandmarker(Delegate.CPU)
                activeDelegate = Delegate.CPU
                detector = landmarker!!
                detector.detectForVideo(mpImage, options, timestamp)
            }

            val points = result.landmarks().firstOrNull()
            val hands = if (points != null && points.size >= 21) {
                // The preview mirrors its front-camera view for the user.
                // Mirror normalized X coordinates, not the source pixel buffer.
                listOf(
                    HandFrame(
                        landmarks = points.map { point ->
                            Landmark(
                                x = (1f - point.x()).coerceIn(0f, 1f),
                                y = point.y().coerceIn(0f, 1f)
                            )
                        },
                        confidence = 1f,
                        handedness = Handedness.UNKNOWN
                    )
                )
            } else {
                emptyList()
            }
            onHands(hands, timestamp)

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (frameCounter.incrementAndGet() % 60 == 0L) {
                Log.d(TAG, "Frames=${frameCounter.get()} frameTime=${elapsed}ms delegate=${activeDelegate}")
            }
        } catch (failure: Exception) {
            Log.e(TAG, "Hand inference failed", failure)
            onError(failure.javaClass.simpleName + ": " + (failure.message ?: "unknown error"))
        } finally {
            mpImage?.close()
            imageProxy.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { landmarker?.close() }
                .onFailure { Log.w(TAG, "Landmarker close failed", it) }
            landmarker = null
            activeDelegate = null
            reusableBitmap?.recycle()
            reusableBitmap = null
        }
    }

    companion object {
        private const val TAG = "AURA-Landmarker"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private val frameCounter = AtomicLong(0L)
    }
}
