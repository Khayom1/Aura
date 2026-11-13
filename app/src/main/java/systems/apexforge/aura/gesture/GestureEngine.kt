package systems.apexforge.aura.gesture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class Landmark(val x: Float, val y: Float)
data class HandFrame(val landmarks: List<Landmark>, val confidence: Float, val handedness: Handedness)
enum class Handedness { LEFT, RIGHT, UNKNOWN }
enum class SystemAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }

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
    data class System(val action: SystemAction) : AuraCommand
    data object CommandModeEntered : AuraCommand
    data object Pause : AuraCommand
    data object NoOp : AuraCommand
}

/**
 * Strict gesture grammar. Commands are emitted only after stable, explicit poses.
 * This class never performs Android actions directly.
 */
class GestureEngine(
    private val minConfidence: Float = 0.88f,
    private val pinchEnterRatio: Float = 0.30f,
    private val pinchExitRatio: Float = 0.42f,
    private val maxZoomStep: Float = 0.12f,
    private val clickMaxMs: Long = 420L,
    private val longPressMs: Long = 560L,
    private val fistArmMs: Long = 850L,
    private val commandModeMs: Long = 4_500L,
    private val commandSwipeDistance: Float = 0.19f
) {
    private var previousDualDistance: Float? = null
    private var previousDualPoints: Pair<Landmark, Landmark>? = null
    private var dualPinchFrames = 0
    private var singlePinchStart: Landmark? = null
    private var singlePinchLast: Landmark? = null
    private var singlePinchStartedAt = 0L
    private var fistStartedAt = 0L
    private var commandModeUntil = 0L
    private var commandAnchor: Landmark? = null
    private var commandMode = false
    private var filteredPointer: Landmark? = null
    private var suppressSinglePinch = false

    init {
        require(minConfidence in 0f..1f)
        require(pinchEnterRatio in 0.1f..0.5f && pinchExitRatio > pinchEnterRatio && pinchExitRatio <= 0.7f)
        require(maxZoomStep in 0f..1f)
        require(clickMaxMs in 100L..800L && longPressMs > clickMaxMs)
        require(fistArmMs in 500L..2_000L && commandModeMs in 1_000L..10_000L)
        require(commandSwipeDistance in 0.1f..0.4f)
    }

    @Synchronized
    fun process(hands: List<HandFrame>, timestampMs: Long): AuraCommand {
        val valid = hands.filter { it.confidence >= minConfidence && it.landmarks.size >= 21 }
            .sortedBy { hand -> hand.landmarks[0].x + hand.landmarks[5].x + hand.landmarks[17].x }
            .take(2)

        if (valid.isEmpty()) {
            val cancelPinchOnRecovery = suppressSinglePinch || singlePinchStart != null || dualPinchFrames > 0
            reset()
            suppressSinglePinch = cancelPinchOnRecovery
            return AuraCommand.NoOp
        }

        if (commandMode) {
            if (timestampMs > commandModeUntil) {
                commandMode = false
                commandAnchor = null
                fistStartedAt = 0L
                return AuraCommand.NoOp
            }
            if (valid.size == 1 && isOpenPalm(valid.first())) {
                commandMode = false
                commandAnchor = null
                return AuraCommand.Pause
            }
            if (valid.size == 1 && isVSign(valid.first())) {
                val point = indexTip(valid.first())
                val anchor = commandAnchor
                if (anchor == null) {
                    commandAnchor = point
                    return AuraCommand.NoOp
                }
                val dx = point.x - anchor.x
                val dy = point.y - anchor.y
                if (hypot(dx, dy) >= commandSwipeDistance) {
                    commandMode = false
                    commandAnchor = null
                    return AuraCommand.System(
                        if (abs(dx) > abs(dy)) {
                            if (dx < 0f) SystemAction.BACK else SystemAction.RECENTS
                        } else if (dy < 0f) {
                            SystemAction.HOME
                        } else if (point.x < 0.5f) {
                            SystemAction.NOTIFICATIONS
                        } else {
                            SystemAction.QUICK_SETTINGS
                        }
                    )
                }
                return AuraCommand.NoOp
            }
            commandAnchor = null
            return AuraCommand.NoOp
        }

        if (valid.size == 1 && isFist(valid.first()) && !isPinching(valid.first())) {
            if (fistStartedAt == 0L) fistStartedAt = timestampMs
            if (timestampMs - fistStartedAt >= fistArmMs) {
                commandMode = true
                commandModeUntil = timestampMs + commandModeMs
                commandAnchor = null
                fistStartedAt = 0L
                clearInteraction()
                return AuraCommand.CommandModeEntered
            }
            return AuraCommand.NoOp
        }
        fistStartedAt = 0L

        if (valid.size == 2) {
            val a = valid[0]
            val b = valid[1]
            if (isPinching(a, dualPinchFrames > 0) && isPinching(b, dualPinchFrames > 0)) {
                val pointA = indexTip(a)
                val pointB = indexTip(b)
                val distance = distance(pointA, pointB)
                val previousDistance = previousDualDistance
                val previousPoints = previousDualPoints
                previousDualDistance = distance
                previousDualPoints = pointA to pointB
                dualPinchFrames++
                suppressSinglePinch = true
                clearSinglePinch()
                if (dualPinchFrames < 3 || previousDistance == null || previousPoints == null || previousDistance <= 0.001f) {
                    return AuraCommand.NoOp
                }
                val delta = ((distance - previousDistance) / previousDistance).coerceIn(-maxZoomStep, maxZoomStep)
                return AuraCommand.Zoom(previousPoints.first, previousPoints.second, pointA, pointB, delta)
            }
            clearDualPinch()
            suppressSinglePinch = false
            clearSinglePinch()
            return if (isOpenPalm(a) && isOpenPalm(b)) AuraCommand.Pause else AuraCommand.NoOp
        }

        clearDualPinch()
        val hand = valid.first()
        if (suppressSinglePinch) {
            if (!isPinching(hand, true)) suppressSinglePinch = false
            clearSinglePinch()
            return AuraCommand.NoOp
        }
        if (isOpenPalm(hand)) {
            clearSinglePinch()
            return AuraCommand.Pause
        }

        if (isPinching(hand, singlePinchStart != null)) {
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
            val previous = filteredPointer
            val filtered = if (previous == null) tip else Landmark(
                previous.x + (tip.x - previous.x) * 0.42f,
                previous.y + (tip.y - previous.y) * 0.42f
            )
            filteredPointer = filtered
            AuraCommand.Pointer(filtered.x.coerceIn(0f, 1f), filtered.y.coerceIn(0f, 1f))
        } else AuraCommand.NoOp
    }

    private fun isPinching(hand: HandFrame, alreadyActive: Boolean = false): Boolean {
        val ratio = distance(hand.landmarks[4], hand.landmarks[8]) /
            max(distance(hand.landmarks[0], hand.landmarks[5]), 0.001f)
        return ratio <= if (alreadyActive) pinchExitRatio else pinchEnterRatio
    }

    private fun isIndexOnly(hand: HandFrame): Boolean {
        val wrist = hand.landmarks[0]
        fun extended(tip: Int, pip: Int) = distance(wrist, hand.landmarks[tip]) >
            distance(wrist, hand.landmarks[pip]) * 1.12f
        return extended(8, 6) && !extended(12, 10) && !extended(16, 14) && !extended(20, 18)
    }

    private fun isVSign(hand: HandFrame): Boolean {
        val wrist = hand.landmarks[0]
        fun extended(tip: Int, pip: Int) = distance(wrist, hand.landmarks[tip]) >
            distance(wrist, hand.landmarks[pip]) * 1.12f
        return extended(8, 6) && extended(12, 10) && !extended(16, 14) && !extended(20, 18)
    }

    private fun isFist(hand: HandFrame): Boolean {
        val wrist = hand.landmarks[0]
        return listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18).all { (tip, pip) ->
            distance(wrist, hand.landmarks[tip]) < distance(wrist, hand.landmarks[pip]) * 1.10f
        }
    }

    private fun isOpenPalm(hand: HandFrame): Boolean {
        val wrist = hand.landmarks[0]
        return listOf(8 to 5, 12 to 9, 16 to 13, 20 to 17).all { (tip, base) ->
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
    private fun clearDualPinch() {
        previousDualDistance = null
        previousDualPoints = null
        dualPinchFrames = 0
    }
    private fun clearInteraction() {
        clearSinglePinch()
        clearDualPinch()
        filteredPointer = null
        suppressSinglePinch = false
    }
    private fun reset() {
        clearInteraction()
        fistStartedAt = 0L
        commandMode = false
        commandModeUntil = 0L
        commandAnchor = null
        filteredPointer = null
    }
}
