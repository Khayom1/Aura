package systems.apexforge.aura.gesture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX -> MediaPipe live video inference. Images are transformed to the
 * front-preview orientation, processed locally, then released immediately.
 */
class HandLandmarkAnalyzer(
    context: android.content.Context,
    private val onHands: (List<HandFrame>, Long) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val closed = AtomicBoolean(false)
    private var lastTimestampMs = 0L
    private val landmarker: HandLandmarker = run {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.65f)
            .setMinHandPresenceConfidence(0.65f)
            .setMinTrackingConfidence(0.65f)
            .build()
        HandLandmarker.createFromOptions(
            context.applicationContext,
            options
        )
    }

    @Synchronized
    override fun analyze(image: ImageProxy) {
        if (closed.get()) {
            image.close()
            return
        }
        var bitmapToRecycle: Bitmap? = null
        try {
            val source = image.toBitmap()
            bitmapToRecycle = source
            val matrix = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f)
            }
            val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (oriented !== source) source.recycle()
            bitmapToRecycle = oriented
            val timestamp = (image.imageInfo.timestamp / 1_000_000L).coerceAtLeast(lastTimestampMs + 1L)
            lastTimestampMs = timestamp
            val result = landmarker.detectForVideo(BitmapImageBuilder(oriented).build(), timestamp)
            val categories = result.handedness()
            val hands = result.landmarks().mapIndexed { index, points ->
                val category = categories.getOrNull(index)?.firstOrNull()
                val side = when (category?.categoryName()?.lowercase()) {
                    "left" -> Handedness.LEFT
                    "right" -> Handedness.RIGHT
                    else -> Handedness.UNKNOWN
                }
                HandFrame(
                    landmarks = points.map { Landmark(it.x(), it.y()) },
                    confidence = (category?.score() ?: 0f).coerceIn(0f, 1f),
                    handedness = side
                )
            }
            onHands(hands, timestamp)
        } catch (failure: Exception) {
            Log.e(TAG, "Hand inference failed", failure)
            onError(failure.javaClass.simpleName + ": " + (failure.message ?: "unknown error"))
        } finally {
            bitmapToRecycle?.recycle()
            image.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { landmarker.close() } catch (failure: Exception) {
                Log.w(TAG, "Landmarker close failed", failure)
            }
        }
    }

    companion object {
        private const val TAG = "AURA-Landmarker"
    }
}
