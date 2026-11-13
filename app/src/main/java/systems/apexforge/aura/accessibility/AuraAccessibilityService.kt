package systems.apexforge.aura.accessibility
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** User-enabled Android touch bridge; no permission is granted automatically. */
class AuraAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float, durationMs: Long = 65L): Boolean {
        if (durationMs !in 40L..250L || !x.isFinite() || !y.isFinite()) return false
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs)).build(),
            null, null
        )
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 260L): Boolean {
        if (durationMs !in 100L..900L || listOf(x1, y1, x2, y2).any { !it.isFinite() }) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs)).build(),
            null, null
        )
    }

    companion object {
        @Volatile var activeInstance: AuraAccessibilityService? = null
            private set
    }
    override fun onServiceConnected() { super.onServiceConnected(); activeInstance = this }
    override fun onDestroy() { activeInstance = null; super.onDestroy() }
}
