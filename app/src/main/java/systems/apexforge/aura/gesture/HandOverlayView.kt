package systems.apexforge.aura.gesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Lightweight local HUD overlay for the detected hand skeletons. */
class HandOverlayView(context: Context) : View(context) {
    @Volatile private var hands: List<HandFrame> = emptyList()
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 217, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        alpha = 205
    }
    private val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 235
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 217, 255)
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 217, 255)
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.BOLD
        )
    }
    private val connections = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )

    fun updateHands(value: List<HandFrame>) {
        hands = value
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = hands
        val radius = 3.2f * density
        snapshot.forEachIndexed { handIndex, hand ->
            val pts = hand.landmarks
            if (pts.size < 21) return@forEachIndexed
            connections.forEach { (a, b) ->
                canvas.drawLine(
                    pts[a].x * width, pts[a].y * height,
                    pts[b].x * width, pts[b].y * height,
                    line
                )
            }
            pts.forEachIndexed { index, point ->
                canvas.drawCircle(
                    point.x * width, point.y * height,
                    if (index == 8 || index == 4) radius * 1.55f else radius,
                    if (index == 8 || index == 4) accent else joint
                )
            }
            val wrist = pts[0]
            canvas.drawText(
                if (hand.handedness == Handedness.UNKNOWN) "HAND ${handIndex + 1}" else hand.handedness.name,
                wrist.x * width + 10f, wrist.y * height - 10f, label
            )
        }
    }
}
