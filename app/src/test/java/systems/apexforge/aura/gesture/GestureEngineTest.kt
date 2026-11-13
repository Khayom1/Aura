package systems.apexforge.aura.gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {
    private fun hand(confidence: Float = 0.99f, x: Float = 0.5f, thumbX: Float = 0.8f): HandFrame {
        val p = MutableList(21) { Landmark(0.5f, 0.5f) }
        p[0] = Landmark(0.5f, 0.8f); p[5] = Landmark(0.4f, 0.6f)
        p[8] = Landmark(x, 0.2f); p[4] = Landmark(thumbX, 0.2f)
        return HandFrame(p, confidence, Handedness.RIGHT)
    }
    @Test fun lowConfidenceInputFailsClosed() {
        assertEquals(AuraCommand.NoOp, GestureEngine().process(listOf(hand(0.4f))))
    }
    @Test fun oneHandProducesNormalizedPointer() {
        val result = GestureEngine().process(listOf(hand(x = 0.63f)))
        assertTrue(result is AuraCommand.Pointer)
        assertEquals(0.63f, (result as AuraCommand.Pointer).x, 0.001f)
    }
    @Test fun noHandsProducesNoOp() {
        assertEquals(AuraCommand.NoOp, GestureEngine().process(emptyList()))
    }
    @Test fun twoPinchedHandsMovingApartEmitPositiveZoom() {
        val engine = GestureEngine()
        val first = listOf(hand(x = 0.35f, thumbX = 0.36f), hand(x = 0.65f, thumbX = 0.66f))
        val second = listOf(hand(x = 0.25f, thumbX = 0.26f), hand(x = 0.75f, thumbX = 0.76f))
        assertEquals(AuraCommand.NoOp, engine.process(first))
        val result = engine.process(second)
        assertTrue(result is AuraCommand.Zoom)
        assertEquals(0.12f, (result as AuraCommand.Zoom).scaleDelta, 0.001f)
    }
}
