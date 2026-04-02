package com.kupstudio.touchmouse.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Simple cursor overlay — small green crosshair dot.
 * Position updated directly from HeadTracker on sensor thread → minimal latency.
 */
class CursorOverlayManager(private val context: Context) {

    companion object {
        // Larger to fit dwell ring outside the cursor
        private const val CURSOR_SIZE = 40
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var isShowing = false

    fun show(x: Float, y: Float) {
        if (isShowing) return
        mainHandler.post {
            try {
                val view = CursorView(context)
                val params = WindowManager.LayoutParams(
                    CURSOR_SIZE, CURSOR_SIZE,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = (x - CURSOR_SIZE / 2).toInt()
                    this.y = (y - CURSOR_SIZE / 2).toInt()
                }
                windowManager.addView(view, params)
                cursorView = view
                cursorParams = params
                isShowing = true
            } catch (e: Exception) {
                com.kupstudio.touchmouse.util.DebugLog.e("CursorOverlay", "show: ${e.message}")
            }
        }
    }

    fun hide() {
        if (!isShowing) return
        mainHandler.post {
            try { cursorView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            cursorView = null
            cursorParams = null
            isShowing = false
        }
    }

    fun updatePosition(x: Float, y: Float) {
        val view = cursorView ?: return
        val params = cursorParams ?: return
        params.x = (x - CURSOR_SIZE / 2).toInt()
        params.y = (y - CURSOR_SIZE / 2).toInt()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    /** Set dwell progress 0.0–1.0 (arc fill around cursor) */
    fun setDwellProgress(progress: Float) {
        cursorView?.setDwellProgress(progress)
    }

    /** Set recenter casting progress 0.0–1.0 (inner fill shrinking toward center) */
    fun setRecenterProgress(progress: Float) {
        cursorView?.setRecenterProgress(progress)
    }

    fun flashClick() {
        mainHandler.post {
            cursorView?.let { view ->
                view.setFlash(true)
                mainHandler.postDelayed({ view.setFlash(false) }, 120)
            }
        }
    }

    private class CursorView(context: Context) : View(context) {

        private var flashing = false
        private var dwellProgress = 0f
        private var recenterProgress = 0f

        // Inner cursor radius
        private val innerR = 8f
        // Dwell ring radius (outer)
        private val dwellR = 17f
        private val dwellStroke = 4f

        // Black outlines for contrast on any background
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val dotOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }
        private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            alpha = 160
        }
        // Dwell: black background ring (always visible when dwell active)
        private val dwellBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = dwellStroke + 2f
            style = Paint.Style.STROKE
        }
        // Dwell: green arc that fills clockwise
        private val dwellFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            strokeWidth = dwellStroke
            style = Paint.Style.STROKE
        }
        // Recenter: crosshair lines that converge inward
        private val recenterBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val recenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            strokeWidth = 2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val arcRect = RectF()

        fun setFlash(on: Boolean) {
            flashing = on
            invalidate()
        }

        fun setDwellProgress(progress: Float) {
            dwellProgress = progress.coerceIn(0f, 1f)
            postInvalidate()
        }

        fun setRecenterProgress(progress: Float) {
            recenterProgress = progress.coerceIn(0f, 1f)
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f

            // Flash fill
            if (flashing) {
                canvas.drawCircle(cx, cy, innerR, flashPaint)
            }

            // Inner cursor: black outline behind, then green ring + dot
            canvas.drawCircle(cx, cy, innerR, outlinePaint)
            canvas.drawCircle(cx, cy, innerR, ringPaint)
            canvas.drawCircle(cx, cy, 3.5f, dotOutlinePaint)
            canvas.drawCircle(cx, cy, 2.5f, dotPaint)

            // Dwell progress: outer ring
            if (dwellProgress > 0f) {
                arcRect.set(cx - dwellR, cy - dwellR, cx + dwellR, cy + dwellR)
                // Full black background ring so the progress is clearly visible
                canvas.drawCircle(cx, cy, dwellR, dwellBgPaint)
                // Green arc fills clockwise from top
                canvas.drawArc(arcRect, -90f, 360f * dwellProgress, false, dwellFillPaint)
            }

            // Recenter casting: 4 crosshair ticks converging inward
            if (recenterProgress > 0f) {
                val outerR = 18f
                val innerTarget = 4f
                // Ticks start at outerR, move toward innerTarget as progress increases
                val tipR = outerR - (outerR - innerTarget) * recenterProgress
                val tailR = outerR
                // Draw 4 lines: top, bottom, left, right
                // Black outline first, then green
                for (paint in arrayOf(recenterBgPaint, recenterPaint)) {
                    canvas.drawLine(cx, cy - tailR, cx, cy - tipR, paint)       // top
                    canvas.drawLine(cx, cy + tailR, cx, cy + tipR, paint)       // bottom
                    canvas.drawLine(cx - tailR, cy, cx - tipR, cy, paint)       // left
                    canvas.drawLine(cx + tailR, cy, cx + tipR, cy, paint)       // right
                }
            }
        }
    }
}
