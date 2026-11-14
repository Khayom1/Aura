package systems.apexforge.aura.gesture

/**
 * Converts MediaPipe normalized points to the upright, front-camera preview
 * coordinate space.
 *
 * MediaPipe HandLandmarker projects returned landmarks back into the ORIGINAL
 * unrotated input-image coordinate system. ImageProcessingOptions changes the
 * inference orientation, not the coordinate space returned to this client.
 * Therefore apply CameraX rotation to the returned point, then mirror X for a
 * front-camera selfie preview.
 */
object CameraCoordinateMapper {
    fun toFrontPreview(x: Float, y: Float, rotationDegrees: Int): Landmark {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        require(rotationDegrees % 90 == 0) { "Camera rotation must be a multiple of 90 degrees" }

        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val rotated = when (normalizedRotation) {
            0 -> Landmark(x, y)
            90 -> Landmark(1f - y, x)
            180 -> Landmark(1f - x, 1f - y)
            270 -> Landmark(y, 1f - x)
            else -> error("Unsupported camera rotation: $rotationDegrees")
        }

        // PreviewView mirrors the front-facing preview. Mirror only after
        // rotating so X is the horizontal axis in the final upright frame.
        return Landmark(
            x = (1f - rotated.x).coerceIn(0f, 1f),
            y = rotated.y.coerceIn(0f, 1f)
        )
    }
}
