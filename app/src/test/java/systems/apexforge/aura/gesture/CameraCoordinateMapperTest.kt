package systems.apexforge.aura.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraCoordinateMapperTest {
    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Landmark) {
        assertEquals(expectedX, actual.x, 0.0001f)
        assertEquals(expectedY, actual.y, 0.0001f)
    }

    @Test fun portraitSensorRotationZeroMirrorsOnlyHorizontalAxis() {
        assertPoint(0.8f, 0.3f, CameraCoordinateMapper.toFrontPreview(0.2f, 0.3f, 0))
    }

    @Test fun clockwiseNinetyDegreesSwapsAxesAndMirrors() {
        assertPoint(0.3f, 0.2f, CameraCoordinateMapper.toFrontPreview(0.2f, 0.3f, 90))
    }

    @Test fun oneHundredEightyDegreesRotatesBothAxesAndMirrors() {
        assertPoint(0.2f, 0.7f, CameraCoordinateMapper.toFrontPreview(0.2f, 0.3f, 180))
    }

    @Test fun clockwiseTwoHundredSeventyDegreesSwapsAxesAndMirrors() {
        assertPoint(0.7f, 0.8f, CameraCoordinateMapper.toFrontPreview(0.2f, 0.3f, 270))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRotation() {
        CameraCoordinateMapper.toFrontPreview(0.2f, 0.3f, 45)
    }
}
