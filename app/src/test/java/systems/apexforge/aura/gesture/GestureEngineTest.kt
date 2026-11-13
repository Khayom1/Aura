package systems.apexforge.aura.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {
    private fun pinchHand(indexX: Float, confidence: Float = 0.99f): HandFrame {
        val p = MutableList(21) { Landmark(0.5f, 0.5f) }
        p[0] = Landmark(0.5f, 0.8f); p[5] = Landmark(0.4f, 0.6f)
        p[8] = Landmark(indexX, 0.2f); p[4] = Landmark(indexX + 0.01f, 0.2f)
        return HandFrame(p, confidence, Handedness.UNKNOWN)
    }

    private fun pointingHand(x: Float): HandFrame {
        val p = MutableList(21) { Landmark(0.5f, 0.5f) }
        p[0] = Landmark(0.5f, 0.8f); p[5] = Landmark(0.4f, 0.6f)
        p[6] = Landmark(x, 0.48f); p[8] = Landmark(x, 0.2f)
        listOf(10 to 12, 14 to 16, 18 to 20).forEach { (pip, tip) ->
            p[pip] = Landmark(0.58f, 0.61f); p[tip] = Landmark(0.58f, 0.72f)
        }
        return HandFrame(p, 0.99f, Handedness.RIGHT)
    }

    private fun fistHand(): HandFrame {
        val p = MutableList(21) { Landmark(0.5f, 0.5f) }
        p[0] = Landmark(0.5f, 0.8f)
        listOf(5, 9, 13, 17).forEach { p[it] = Landmark(0.5f, 0.55f) }
        listOf(6, 10, 14, 18).forEach { p[it] = Landmark(0.5f, 0.58f) }
        listOf(8, 12, 16, 20).forEach { p[it] = Landmark(0.5f, 0.68f) }
        p[4] = Landmark(0.43f, 0.65f)
        return HandFrame(p, 0.99f, Handedness.RIGHT)
    }

    private fun vSign(x: Float): HandFrame {
        val p = MutableList(21) { Landmark(0.5f, 0.5f) }
        p[0] = Landmark(0.5f, 0.8f)
        p[5] = Landmark(0.42f, 0.6f); p[6] = Landmark(x, 0.42f); p[8] = Landmark(x, 0.15f)
        p[9] = Landmark(0.58f, 0.6f); p[10] = Landmark(x + 0.08f, 0.42f); p[12] = Landmark(x + 0.08f, 0.15f)
        listOf(14, 18).forEach { p[it] = Landmark(0.55f, 0.61f) }
        listOf(16, 20).forEach { p[it] = Landmark(0.55f, 0.72f) }
        return HandFrame(p, 0.99f, Handedness.RIGHT)
    }

    @Test fun lowConfidenceInputFailsClosed() {
        assertEquals(AuraCommand.NoOp, GestureEngine().process(listOf(pointingHand(0.63f).copy(confidence = 0.4f)), 100L))
    }
    @Test fun oneHandProducesNormalizedPointer() {
        val result = GestureEngine().process(listOf(pointingHand(0.63f)), 100L)
        assertTrue(result is AuraCommand.Pointer)
        assertEquals(0.63f, (result as AuraCommand.Pointer).x, 0.001f)
    }
    @Test fun noHandsProducesNoOp() {
        assertEquals(AuraCommand.NoOp, GestureEngine().process(emptyList(), 100L))
    }
    @Test fun twoPinchedHandsMovingApartEmitPositiveZoom() {
        val engine = GestureEngine()
        val first = listOf(pinchHand(0.35f), pinchHand(0.65f))
        val second = listOf(pinchHand(0.25f), pinchHand(0.75f))
        assertEquals(AuraCommand.NoOp, engine.process(first, 100L))
        assertEquals(AuraCommand.NoOp, engine.process(first, 133L))
        val result = engine.process(second, 166L)
        assertTrue(result is AuraCommand.Zoom)
        assertTrue((result as AuraCommand.Zoom).scaleDelta > 0f)
        assertEquals(0.12f, result.scaleDelta, 0.001f)
    }
    @Test fun singlePinchReleaseEmitsOneTap() {
        val engine = GestureEngine()
        val pinch = pinchHand(0.4f)
        engine.process(listOf(pinch), 100L)
        engine.process(listOf(pinch), 180L)
        val result = engine.process(listOf(pointingHand(0.4f)), 240L)
        assertTrue(result is AuraCommand.Tap)
        assertTrue(engine.process(listOf(pointingHand(0.4f)), 273L) is AuraCommand.Pointer)
    }
    @Test fun lostTrackingCancelsPinchWithoutClick() {
        val engine = GestureEngine()
        engine.process(listOf(pinchHand(0.4f)), 100L)
        assertEquals(AuraCommand.NoOp, engine.process(emptyList(), 200L))
        assertEquals(AuraCommand.NoOp, engine.process(listOf(pinchHand(0.4f)), 240L))
        assertEquals(AuraCommand.NoOp, engine.process(listOf(pointingHand(0.4f)), 273L))
        assertTrue(engine.process(listOf(pointingHand(0.4f)), 306L) is AuraCommand.Pointer)
    }
    @Test fun dualPinchReleaseCannotTurnIntoSingleHandClick() {
        val engine = GestureEngine()
        val pair = listOf(pinchHand(0.35f), pinchHand(0.65f))
        engine.process(pair, 100L)
        engine.process(pair, 133L)
        assertTrue(engine.process(pair, 166L) is AuraCommand.Zoom)
        assertEquals(AuraCommand.NoOp, engine.process(listOf(pinchHand(0.4f)), 200L))
        assertEquals(AuraCommand.NoOp, engine.process(listOf(pointingHand(0.4f)), 233L))
        assertTrue(engine.process(listOf(pointingHand(0.4f)), 266L) is AuraCommand.Pointer)
    }

    @Test fun uncertainSecondHandBlocksSingleHandPinchClick() {
        val engine = GestureEngine()
        assertEquals(AuraCommand.NoOp, engine.process(listOf(pinchHand(0.4f), pinchHand(0.7f, 0.4f)), 100L))
        assertEquals(AuraCommand.NoOp, engine.process(listOf(pinchHand(0.4f)), 133L))
        assertEquals(AuraCommand.NoOp, engine.process(listOf(pointingHand(0.4f)), 166L))
        assertTrue(engine.process(listOf(pointingHand(0.4f)), 199L) is AuraCommand.Pointer)
    }

    @Test fun fistArmsCommandModeAndLeftVSwipeMeansBack() {
        val engine = GestureEngine()
        assertEquals(AuraCommand.CommandModeArming, engine.process(listOf(fistHand()), 100L))
        assertEquals(AuraCommand.CommandModeEntered, engine.process(listOf(fistHand()), 1_000L))
        assertEquals(AuraCommand.NoOp, engine.process(listOf(vSign(0.6f)), 1_033L))
        assertEquals(AuraCommand.System(SystemAction.BACK), engine.process(listOf(vSign(0.25f)), 1_066L))
    }
}
