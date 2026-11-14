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
import kotlin.math.roundToInt
import systems.apexforge.aura.gesture.SystemAction

/**
 * Touch injection and a non-interactive full-screen cursor layer.
 *
 * The overlay window is created once per tracking session. Frame updates only
 * change two normalized floats and invalidate its Canvas; no WindowManager
 * relayout IPC is performed for every camera frame.
 */
class AuraAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var cursor: CursorView? = null
    private val cachedMetrics = DisplayMetrics()
    private var lastDisplayRotation = -1

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
        var view = cursor
        if (view == null) {
            view = CursorView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            try {
                wm.addView(view, params)
                cursor = view
            } catch (_: Exception) {
                cursor = null
                return
            }
        }
        view.updatePosition(normalizedX, normalizedY)
    }

    fun hidePointer() {
        val view = cursor ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) { }
        cursor = null
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

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int = windowManager?.defaultDisplay?.rotation ?: -1

    @Suppress("DEPRECATION")
    private fun refreshDisplayMetrics() {
        cachedMetrics.setToDefaults()
        windowManager?.defaultDisplay?.let { display ->
            display.getRealMetrics(cachedMetrics)
            lastDisplayRotation = display.rotation
        }
    }

    private fun screenPoint(x: Float, y: Float): Pair<Float, Float> {
        if (cachedMetrics.widthPixels <= 0 || cachedMetrics.heightPixels <= 0 ||
            currentDisplayRotation() != lastDisplayRotation
        ) {
            refreshDisplayMetrics()
        }
        val maxX = (cachedMetrics.widthPixels - 1).coerceAtLeast(1).toFloat()
        val maxY = (cachedMetrics.heightPixels - 1).coerceAtLeast(1).toFloat()
        return (x * cachedMetrics.widthPixels).coerceIn(1f, maxX) to
            (y * cachedMetrics.heightPixels).coerceIn(1f, maxY)
    }

    private fun valid(x: Float, y: Float) = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f

    override fun onDestroy() {
        hidePointer()
        activeInstance = null
        windowManager = null
        super.onDestroy()
    }

    private class CursorView(context: Context) : View(context) {
        @Volatile private var normalizedX = 0.5f
        @Volatile private var normalizedY = 0.5f
        private val density = resources.displayMetrics.density
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 217, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3.0f * density
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 235
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 217, 255)
            style = Paint.Style.FILL
            alpha = 40
        }

        fun updatePosition(x: Float, y: Float) {
            normalizedX = x
            normalizedY = y
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val cx = normalizedX * width
            val cy = normalizedY * height
            val radius = 18f * density
            canvas.drawCircle(cx, cy, radius * 1.25f, glow)
            canvas.drawCircle(cx, cy, radius * 0.78f, outer)
            canvas.drawCircle(cx, cy, radius * 0.16f, inner)
        }
    }

    companion object {
        @Volatile var activeInstance: AuraAccessibilityService? = null
            private set
    }
}
