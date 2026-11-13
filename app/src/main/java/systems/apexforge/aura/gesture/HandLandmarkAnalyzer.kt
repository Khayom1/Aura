package systems.apexforge.aura.gesture

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX RGBA -> MediaPipe live video inference.
 *
 * The camera frame is passed directly to MediaPipe with rotation metadata.
 * This avoids allocating, rotating and mirroring a full-size Bitmap for every
 * camera frame. Front-camera mirroring is applied only to normalized landmarks.
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
        HandLandmarker.createFromOptions(context.applicationContext, options)
    }

    @Synchronized
    override fun analyze(image: ImageProxy) {
        if (closed.get()) {
            image.close()
            return
        }

        try {
            val mediaImage = image.image
                ?: throw IllegalStateException("CameraX did not provide an RGBA media image")
            val mpImage = MediaImageBuilder(mediaImage).build()
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(image.imageInfo.rotationDegrees)
                .build()

            val timestamp = (image.imageInfo.timestamp / 1_000_000L)
                .coerceAtLeast(lastTimestampMs + 1L)
            lastTimestampMs = timestamp

            val result = landmarker.detectForVideo(mpImage, processingOptions, timestamp)
            val categories = result.handedness()
            val hands = result.landmarks().mapIndexed { index, points ->
                val category = categories.getOrNull(index)?.firstOrNull()
                // MediaPipe handedness assumes mirrored selfie input. We now
                // pass the camera image unmirrored, so invert the reported side.
                val side = when (category?.categoryName()?.lowercase()) {
                    "left" -> Handedness.RIGHT
                    "right" -> Handedness.LEFT
                    else -> Handedness.UNKNOWN
                }
                HandFrame(
                    // Match the mirrored front-camera preview without copying
                    // and mirroring the entire source frame.
                    landmarks = points.map { Landmark((1f - it.x()).coerceIn(0f, 1f), it.y().coerceIn(0f, 1f)) },
                    confidence = if (points.size >= 21) 1f else 0f,
                    handedness = side
                )
            }
            onHands(hands, timestamp)
        } catch (failure: Exception) {
            Log.e(TAG, "Hand inference failed", failure)
            onError(failure.javaClass.simpleName + ": " + (failure.message ?: "unknown error"))
        } finally {
            image.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                landmarker.close()
            } catch (failure: Exception) {
                Log.w(TAG, "Landmarker close failed", failure)
            }
        }
    }

    companion object {
        private const val TAG = "AURA-Landmarker"
    }
}
