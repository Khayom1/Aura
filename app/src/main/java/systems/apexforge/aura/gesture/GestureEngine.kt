package systems.apexforge.aura.gesture

import kotlin.math.hypot
import kotlin.math.max

data class Landmark(val x: Float, val y: Float)
data class HandFrame(
    val landmarks: List<Landmark>,
    val confidence: Float,
    val handedness: Handedness
)
enum class Handedness { LEFT, RIGHT, UNKNOWN }

sealed interface AuraCommand {
    data class Pointer(val x: Float, val y: Float) : AuraCommand
    data class Tap(val x: Float, val y: Float) : AuraCommand
    data class LongPress(val x: Float, val y: Float) : AuraCommand
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long) : AuraCommand
    data class Zoom(
        val fromA: Landmark, val fromB: Landmark,
        val toA: Landmark, val toB: Landmark,
        val scaleDelta: Float
    ) : AuraCommand
    data object Pause : AuraCommand
    data object NoOp : AuraCommand
}

/**
 * Strict gesture grammar. It emits intent only; callers must additionally gate
 * all commands on an explicitly armed session and a live accessibility service.
 */
class GestureEngine(
    private val minConfidence: Float = 0.88f,
    private val pinchEnterRatio: Float = 0.30f,
    private val pinchExitRatio: Float = 0.42f,
    private val maxZoomStep: Float = 0.12f,
    private val clickMaxMs: Long = 420L,
    private val longPressMs: Long = 560L
) {
    private var previousDualDistance: Float? = null
    private var previousDualPoints: Pair<Landmark, Landmark>? = null
    private var dualPinchFrames = 0
    private var singlePinchStart: Landmark? = null
    private var singlePinchLast: Landmark? = null
    private var singlePinchStartedAt = 0L

    init {
        require(minConfidence in 0f..1f)
        require(pinchEnterRatio > 0f && pinchExitRatio > pinchEnterRatio)
        require(maxZoomStep in 0f..1f)
        require(clickMaxMs in 100L..800L && longPressMs > clickMaxMs)
    }

    @Synchronized
    fun process(hands: List<HandFrame>, timestampMs: Long): AuraCommand {
        val valid = hands.filter { it.confidence >= minConfidence && it.landmarks.size >= 21 }
            .sortedBy { hand -> hand.landmarks[0].x + hand.landmarks[5].x + hand.landmarks[17].x }
            .take(2)
        if (valid.isEmpty()) {
            reset()
            return AuraCommand.NoOp
        }

        if (valid.size == 2) {
            val a = valid[0]
            val b = valid[1]
            if (isPinching(a) && isPinching(b)) {
                val pointA = indexTip(a)
                val pointB = indexTip(b)
                val distance = distance(pointA, pointB)
                val previousDistance = previousDualDistance
                val previousPoints = previousDualPoints
                previousDualDistance = distance
                previousDualPoints = pointA to pointB
                dualPinchFrames++
                clearSinglePinch()
                if (dualPinchFrames < 3 || previousDistance == null || previousPoints == null || previousDistance <= 0.001f) {
                    return AuraCommand.NoOp
                }
                val delta = ((distance - previousDistance) / previousDistance).coerceIn(-maxZoomStep, maxZoomStep)
                return AuraCommand.Zoom(previousPoints.first, previousPoints.second, pointA, pointB, delta)
            }
            previousDualDistance = null
            previousDualPoints = null
            dualPinchFrames = 0
            clearSinglePinch()
            return if (isOpenPalm(a) && isOpenPalm(b)) AuraCommand.Pause else AuraCommand.NoOp
        }

        previousDualDistance = null
        previousDualPoints = null
        dualPinchFrames = 0
        val hand = valid.first()
        if (isOpenPalm(hand)) {
            clearSinglePinch()
            return AuraCommand.Pause
        }

        if (isPinching(hand)) {
            val point = indexTip(hand)
            if (singlePinchStart == null) {
                singlePinchStart = point
                singlePinchLast = point
                singlePinchStartedAt = timestampMs
            } else {
                singlePinchLast = point
            }
            return AuraCommand.NoOp
        }

        if (singlePinchStart != null) {
            val start = singlePinchStart!!
            val end = singlePinchLast ?: start
            val elapsed = (timestampMs - singlePinchStartedAt).coerceAtLeast(0L)
            clearSinglePinch()
            val moved = distance(start, end) > 0.035f
            return when {
                moved -> AuraCommand.Swipe(start.x, start.y, end.x, end.y, elapsed.coerceIn(120L, 900L))
                elapsed >= longPressMs -> AuraCommand.LongPress(end.x, end.y)
                elapsed <= clickMaxMs -> AuraCommand.Tap(end.x, end.y)
                else -> AuraCommand.NoOp
            }
        }

        return if (isIndexOnly(hand)) {
            val tip = indexTip(hand)
            AuraCommand.Pointer(tip.x.coerceIn(0f, 1f), tip.y.coerceIn(0f, 1f))
        } else AuraCommand.NoOp
    }

    private fun isPinching(hand: HandFrame): Boolean {
        val ratio = distance(hand.landmarks[4], hand.landmarks[8]) /
            max(distance(hand.landmarks[0], hand.landmarks[5]), 0.001f)
        return ratio <= pinchEnterRatio
    }

    private fun isIndexOnly(hand: HandFrame): Boolean {
        val wrist = hand.landmarks[0]
        fun extended(tip: Int, pip: Int): Boolean =
            distance(wrist, hand.landmarks[tip]) > distance(wrist, hand.landmarks[pip]) * 1.12f
        return extended(8, 6) && !extended(12, 10) && !extended(16, 14) && !extended(20, 18)
    }

    private fun isOpenPalm(hand: HandFrame): Boolean {
        val wrist = hand.landmarks[0]
        return listOf(8, 12, 16, 20).zip(listOf(5, 9, 13, 17)).all { (tip, base) ->
            distance(wrist, hand.landmarks[tip]) > distance(wrist, hand.landmarks[base]) * 1.18f
        }
    }

    private fun indexTip(hand: HandFrame) = hand.landmarks[8]
    private fun distance(a: Landmark, b: Landmark) = hypot(a.x - b.x, a.y - b.y)
    private fun clearSinglePinch() {
        singlePinchStart = null
        singlePinchLast = null
        singlePinchStartedAt = 0L
    }
    private fun reset() {
        previousDualDistance = null
        previousDualPoints = null
        dualPinchFrames = 0
        clearSinglePinch()
    }
}
