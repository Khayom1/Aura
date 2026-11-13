package systems.apexforge.aura.gesture
import kotlin.math.hypot
import kotlin.math.max

data class Landmark(val x: Float, val y: Float)
data class HandFrame(val landmarks: List<Landmark>, val confidence: Float, val handedness: Handedness)
enum class Handedness { LEFT, RIGHT, UNKNOWN }
sealed interface AuraCommand {
    data class Pointer(val x: Float, val y: Float) : AuraCommand
    data class Zoom(val scaleDelta: Float) : AuraCommand
    data object Pause : AuraCommand
    data object NoOp : AuraCommand
}

/** Deterministic, fail-closed gesture core. Coordinates are normalized to [0, 1]. */
class GestureEngine(
    private val minConfidence: Float = 0.88f,
    private val pinchEnterRatio: Float = 0.30f,
    private val pinchExitRatio: Float = 0.42f,
    private val maxZoomStep: Float = 0.12f
) {
    private var previousPinchDistance: Float? = null
    private var wasDualPinching = false

    init {
        require(minConfidence in 0f..1f)
        require(pinchEnterRatio > 0f && pinchExitRatio > pinchEnterRatio)
        require(maxZoomStep in 0f..1f)
    }

    fun process(hands: List<HandFrame>): AuraCommand {
        val valid = hands.filter { it.confidence >= minConfidence && it.landmarks.size >= 21 }
        if (valid.isEmpty()) {
            reset()
            return AuraCommand.NoOp
        }
        if (valid.size >= 2) {
            val pair = valid.take(2)
            if (isPinching(pair[0]) && isPinching(pair[1])) {
                val distance = distance(indexTip(pair[0]), indexTip(pair[1]))
                val previous = previousPinchDistance
                previousPinchDistance = distance
                wasDualPinching = true
                if (previous == null || previous <= 0.001f) return AuraCommand.NoOp
                val raw = (distance - previous) / previous
                return AuraCommand.Zoom(raw.coerceIn(-maxZoomStep, maxZoomStep))
            }
            previousPinchDistance = null
            wasDualPinching = false
            return if (pair.all { isOpenPalm(it) }) AuraCommand.Pause else AuraCommand.NoOp
        }
        previousPinchDistance = null
        wasDualPinching = false
        val hand = valid.first()
        if (isOpenPalm(hand)) return AuraCommand.Pause
        if (isPinching(hand)) return AuraCommand.NoOp
        val tip = indexTip(hand)
        return AuraCommand.Pointer(tip.x.coerceIn(0f, 1f), tip.y.coerceIn(0f, 1f))
    }

    private fun isPinching(hand: HandFrame): Boolean {
        val thumb = hand.landmarks[4]
        val index = hand.landmarks[8]
        val scale = max(distance(hand.landmarks[0], hand.landmarks[5]), 0.001f)
        val ratio = distance(thumb, index) / scale
        return ratio <= pinchEnterRatio || (ratio < pinchExitRatio && wasDualPinching)
    }
    private fun isOpenPalm(hand: HandFrame): Boolean {
        val wrist = hand.landmarks[0]
        return listOf(8, 12, 16, 20).zip(listOf(5, 9, 13, 17)).all { (tip, base) ->
            hand.landmarks[tip].y < hand.landmarks[base].y &&
                distance(wrist, hand.landmarks[tip]) > distance(wrist, hand.landmarks[base])
        }
    }
    private fun indexTip(hand: HandFrame) = hand.landmarks[8]
    private fun distance(a: Landmark, b: Landmark) = hypot(a.x - b.x, a.y - b.y)
    private fun reset() { previousPinchDistance = null; wasDualPinching = false }
}
