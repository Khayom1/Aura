package systems.apexforge.aura.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import systems.apexforge.aura.gesture.SystemAction

/** Single-pointer touch injection and cursor overlay; user must enable this service. */
class AuraAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var cursor: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private val cachedMetrics = DisplayMetrics()
    private var lastCursorX = Int.MIN_VALUE
    private var lastCursorY = Int.MIN_VALUE

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshDisplayMetrics()
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
        val pointerView = cursor ?: return
        if (cachedMetrics.widthPixels <= 0 || cachedMetrics.heightPixels <= 0) refreshDisplayMetrics()
        val x = (normalizedX * cachedMetrics.widthPixels - size / 2f).roundToInt()
            .coerceIn(0, (cachedMetrics.widthPixels - size).coerceAtLeast(0))
        val y = (normalizedY * cachedMetrics.heightPixels - size / 2f).roundToInt()
            .coerceIn(0, (cachedMetrics.heightPixels - size).coerceAtLeast(0))
        if (abs(x - lastCursorX) < 1 && abs(y - lastCursorY) < 1) return
        params.x = x
        params.y = y
        try {
            wm.updateViewLayout(pointerView, params)
            lastCursorX = x
            lastCursorY = y
        } catch (_: Exception) { }
    }

    fun hidePointer() {
        val view = cursor ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) { }
        cursor = null
        cursorParams = null
        lastCursorX = Int.MIN_VALUE
        lastCursorY = Int.MIN_VALUE
    }

    fun performSystemAction(action: SystemAction): Boolean {
        val global = when (action) {
            SystemAction.BACK -> GLOBAL_ACTION_BACK
            SystemAction.HOME -> GLOBAL_ACTION_HOME
            SystemAction.RECENTS -> GLOBAL_ACTION_RECENTS
            SystemAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            SystemAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
        }
        return performGlobalAction(global)
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

    private fun dispatchSingle(x: Float, y: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build(),
            null, null
        )
    }

    /** Accessibility gestures use physical display coordinates, not app-window coordinates. */
    @Suppress("DEPRECATION")
    private fun refreshDisplayMetrics() {
        cachedMetrics.setToDefaults()
        windowManager?.defaultDisplay?.getRealMetrics(cachedMetrics)
    }

    private fun screenPoint(x: Float, y: Float): Pair<Float, Float> {
        if (cachedMetrics.widthPixels <= 0 || cachedMetrics.heightPixels <= 0) refreshDisplayMetrics()
        val maxX = (cachedMetrics.widthPixels - 1).coerceAtLeast(1).toFloat()
        val maxY = (cachedMetrics.heightPixels - 1).coerceAtLeast(1).toFloat()
        return (x * cachedMetrics.widthPixels).coerceIn(1f, maxX) to
            (y * cachedMetrics.heightPixels).coerceIn(1f, maxY)
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
            val cx = width / 2f
            val cy = height / 2f
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