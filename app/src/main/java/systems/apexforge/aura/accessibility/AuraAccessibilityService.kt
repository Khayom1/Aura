package systems.apexforge.aura.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

/** Touch injection and a non-interactive cursor overlay; user must enable this service. */
class AuraAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var cursor: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun showPointer(normalizedX: Float, normalizedY: Float) {
        if (!valid(normalizedX, normalizedY)) return
        val wm = windowManager ?: return
        val size = dp(42)
        if (cursor == null) {
            val view = CursorView(this)
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                wm.addView(view, params)
                cursor = view
                cursorParams = params
            } catch (_: Exception) { return }
        }
        val params = cursorParams ?: return
        val metrics = resources.displayMetrics
        params.x = (normalizedX * metrics.widthPixels - size / 2f).roundToInt().coerceIn(0, metrics.widthPixels - size)
        params.y = (normalizedY * metrics.heightPixels - size / 2f).roundToInt().coerceIn(0, metrics.heightPixels - size)
        try { wm.updateViewLayout(cursor, params) } catch (_: Exception) { }
    }

    fun hidePointer() {
        val view = cursor ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) { }
        cursor = null
        cursorParams = null
    }

    fun tapNormalized(x: Float, y: Float): Boolean {
        if (!valid(x, y)) return false
        val p = screenPoint(x, y)
        return dispatchSingle(p.first, p.second, 65L)
    }

    fun longPressNormalized(x: Float, y: Float): Boolean {
        if (!valid(x, y)) return false
        val p = screenPoint(x, y)
        return dispatchSingle(p.first, p.second, 650L)
    }

    fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (!valid(x1, y1) || !valid(x2, y2) || durationMs !in 120L..900L) return false
        val a = screenPoint(x1, y1)
        val b = screenPoint(x2, y2)
        val path = Path().apply { moveTo(a.first, a.second); lineTo(b.first, b.second) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs)).build(),
            null, null
        )
    }

    /** Sends a real two-pointer Android gesture, with both fingers moving concurrently. */
    fun pinchNormalized(
        fromAX: Float, fromAY: Float, fromBX: Float, fromBY: Float,
        toAX: Float, toAY: Float, toBX: Float, toBY: Float
    ): Boolean {
        val values = listOf(fromAX, fromAY, fromBX, fromBY, toAX, toAY, toBX, toBY)
        if (values.any { !it.isFinite() || it !in 0f..1f }) return false
        val a = screenPoint(fromAX, fromAY); val b = screenPoint(toAX, toAY)
        val c = screenPoint(fromBX, fromBY); val d = screenPoint(toBX, toBY)
        val pathA = Path().apply { moveTo(a.first, a.second); lineTo(b.first, b.second) }
        val pathB = Path().apply { moveTo(c.first, c.second); lineTo(d.first, d.second) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pathA, 0L, 110L))
            .addStroke(GestureDescription.StrokeDescription(pathB, 0L, 110L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchSingle(x: Float, y: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build(),
            null, null
        )
    }

    private fun screenPoint(x: Float, y: Float): Pair<Float, Float> {
        val metrics = resources.displayMetrics
        return (x * metrics.widthPixels).coerceIn(1f, metrics.widthPixels - 1f) to
            (y * metrics.heightPixels).coerceIn(1f, metrics.heightPixels - 1f)
    }

    private fun valid(x: Float, y: Float) = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        hidePointer()
        activeInstance = null
        windowManager = null
        super.onDestroy()
    }

    private class CursorView(context: Context) : View(context) {
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 217, 255); style = Paint.Style.STROKE; strokeWidth = 3.2f
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL; alpha = 220
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 217, 255); style = Paint.Style.FILL; alpha = 35
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, width * 0.43f, glow)
            canvas.drawCircle(cx, cy, width * 0.31f, outer)
            canvas.drawCircle(cx, cy, width * 0.07f, inner)
        }
    }

    companion object {
        @Volatile var activeInstance: AuraAccessibilityService? = null
            private set
    }
}
