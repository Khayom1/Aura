package systems.apexforge.aura.gesture

import android.media.Image
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX RGBA frame -> MediaPipe MPImage -> one-hand landmarks.
 *
 * No per-frame Bitmap allocation, app-side Matrix rotation, or mirrored Bitmap
 * copy. Rotation is passed as image metadata. The landmarker is created lazily
 * on the analyzer thread so the GPU delegate is initialized and used there.
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

    @Synchronized
    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }

        var mpImage: MPImage? = null
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val mediaImage: Image = imageProxy.image
                ?: throw IllegalStateException("CameraX returned no media image")
            // The service requests CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888.
            // Wrap the camera image directly and keep it alive until inference ends.
            mpImage = MediaImageBuilder(mediaImage).build()

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
                // PreviewView mirrors its front-camera preview for the user.
                // Analysis is not mirrored, so mirror normalized X coordinates
                // rather than creating a second transformed pixel buffer.
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
        }
    }

    companion object {
        private const val TAG = "AURA-Landmarker"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private val frameCounter = AtomicLong(0L)
    }
}
