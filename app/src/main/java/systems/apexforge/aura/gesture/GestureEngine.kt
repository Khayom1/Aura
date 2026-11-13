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
    data class System(val action: SystemAction) : AuraCommand
    data object CommandModeEntered : AuraCommand
    data object CommandModeArming : AuraCommand
    data object Pause : AuraCommand
    data object NoOp : AuraCommand
}

/**
 * Single-hand gesture grammar. Extra hands are ignored; the camera model is
 * configured to detect only one hand. All actions require a valid 21-point pose.
 */
class GestureEngine(
    private val minConfidence: Float = 0.88f,
    private val pinchEnterRatio: Float = 0.30f,
    private val pinchExitRatio: Float = 0.42f,
    private val clickMaxMs: Long = 420L,
    private val longPressMs: Long = 560L,
    private val fistArmMs: Long = 850L,
    private val commandModeMs: Long = 4_500L,
    private val commandSwipeDistance: Float = 0.19f
) {
    private var singlePinchStart: Landmark? = null
    private var singlePinchLast: Landmark? = null
    private var singlePinchStartedAt = 0L
    private var suppressPinchUntilRelease = false
    private var fistStartedAt = 0L
    private var commandModeUntil = 0L
    private var commandAnchor: Landmark? = null
    private var commandMode = false
    private var filteredPointer: Landmark? = null

    init {
        require(minConfidence in 0f..1f)
        require(pinchEnterRatio in 0.1f..0.5f && pinchExitRatio > pinchEnterRatio && pinchExitRatio <= 0.7f)
        require(clickMaxMs in 100L..800L && longPressMs > clickMaxMs)
        require(fistArmMs in 500L..2_000L && commandModeMs in 1_000L..10_000L)
        require(commandSwipeDistance in 0.1f..0.4f)
    }

    @Synchronized
    fun process(hands: List<HandFrame>, timestampMs: Long): AuraCommand {
        val hand = hands.firstOrNull { candidate ->
            candidate.confidence.isFinite() && candidate.confidence >= minConfidence &&
                candidate.landmarks.size >= 21 &&
                candidate.landmarks.all { point ->
                    point.x.isFinite() && point.y.isFinite() &&
                        point.x in 0f..1f && point.y in 0f..1f
                }
        }

        if (hand == null) {
            val cancelPinchOnRecovery = suppressPinchUntilRelease || singlePinchStart != null
            reset()
            suppressPinchUntilRelease = cancelPinchOnRecovery
            return AuraCommand.NoOp
        }

        if (commandMode) {
            if (timestampMs > commandModeUntil) {
                commandMode = false
                commandAnchor = null
                fistStartedAt = 0L
                return AuraCommand.Pause
            }
            if (isOpenPalm(hand)) {
                commandMode = false
                commandAnchor = null
                return AuraCommand.Pause
            }
            if (isVSign(hand)) {
                val point = indexTip(hand)
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

        if (suppressPinchUntilRelease) {
            if (!isPinching(hand, alreadyActive = true)) suppressPinchUntilRelease = false
            clearSinglePinch()
            return AuraCommand.NoOp
        }

        if (isFist(hand) && !isPinching(hand)) {
            if (fistStartedAt == 0L) fistStartedAt = timestampMs
            if (timestampMs - fistStartedAt >= fistArmMs) {
                commandMode = true
                commandModeUntil = timestampMs + commandModeMs
                commandAnchor = null
                fistStartedAt = 0L
                clearSinglePinch()
                filteredPointer = null
                return AuraCommand.CommandModeEntered
            }
            return AuraCommand.CommandModeArming
        }
        if (fistStartedAt > 0L) {
            fistStartedAt = 0L
            return AuraCommand.Pause
        }

        if (isOpenPalm(hand)) {
            clearSinglePinch()
            filteredPointer = null
            return AuraCommand.Pause
        }

        if (isPinching(hand, alreadyActive = singlePinchStart != null)) {
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
            // Higher alpha reduces cursor lag while retaining a small amount of
            // temporal smoothing against landmark jitter.
            val filtered = if (previous == null) tip else Landmark(
                previous.x + (tip.x - previous.x) * 0.72f,
                previous.y + (tip.y - previous.y) * 0.72f
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
        return (intArrayOf(8, 12, 16, 20)).all { tip ->
            val pip = tip - 2
            distance(wrist, hand.landmarks[tip]) < distance(wrist, hand.landmarks[pip]) * 1.10f
        }
    }

    private fun isOpenPalm(hand: HandFrame): Boolean {
        val wrist = hand.landmarks[0]
        val tips = intArrayOf(8, 12, 16, 20)
        val bases = intArrayOf(5, 9, 13, 17)
        return tips.indices.all { index ->
            distance(wrist, hand.landmarks[tips[index]]) >
                distance(wrist, hand.landmarks[bases[index]]) * 1.18f
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
        clearSinglePinch()
        fistStartedAt = 0L
        commandMode = false
        commandModeUntil = 0L
        commandAnchor = null
        filteredPointer = null
    }
}
