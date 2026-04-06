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
 * Position updated directly from HeadTracker on sensor thread -> minimal latency.
 */
class CursorOverlayManager(private val context: Context) {

    companion object {
        // Larger to fit dwell ring + selection labels
        private const val CURSOR_SIZE = 160
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
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
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

    /** Set dwell progress 0.0-1.0 (arc fill around cursor) */
    fun setDwellProgress(progress: Float) {
        cursorView?.setDwellProgress(progress)
    }

    /** Show/hide dwell mode indicator */
    fun setDwellMode(active: Boolean) {
        cursorView?.setDwellMode(active)
    }

    /** Show/hide scroll mode indicator */
    fun setScrollMode(active: Boolean) {
        cursorView?.setScrollMode(active)
        if (!active) cursorView?.setScrollDir(0f, 0f)
    }

    /** Set active scroll direction (0,0 = none) */
    fun setScrollDir(dx: Float, dy: Float) {
        cursorView?.setScrollDir(dx, dy)
    }

    /** Show/hide selection mode overlay */
    fun setSelectionMode(active: Boolean) {
        cursorView?.setSelectionMode(active)
    }

    /** Set selection direction (0=none, 1=up, 2=down, 3=left, 4=right) + cast progress 0-1 */
    fun setSelectionDir(dir: Int, progress: Float) {
        cursorView?.setSelectionDir(dir, progress)
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
        private var dwellMode = false
        private var scrollMode = false
        private var scrollDirX = 0f
        private var scrollDirY = 0f
        private var inSelectionMode = false
        private var selDir = 0
        private var selProgress = 0f

        // Inner cursor radius
        private val innerR = 8f
        // Dwell ring radius (outer)
        private val dwellR = 17f
        private val dwellStroke = 4f

        // Selection mode layout
        private val selRadius = 55f      // distance from center to label

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
        // Scroll mode: arrow indicator
        private val scrollArrowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 3.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val scrollArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Selection mode paints
        private val selLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            textSize = 16f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
        }
        private val selLabelDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 255, 0)
            textSize = 14f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
        }
        private val selLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 16f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val selArcBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 0, 0, 0)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        private val selArcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            strokeWidth = 3f
            style = Paint.Style.STROKE
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

        fun setDwellMode(active: Boolean) {
            dwellMode = active
            postInvalidate()
        }

        fun setScrollMode(active: Boolean) {
            scrollMode = active
            if (!active) { scrollDirX = 0f; scrollDirY = 0f }
            postInvalidate()
        }

        fun setScrollDir(dx: Float, dy: Float) {
            scrollDirX = dx
            scrollDirY = dy
            postInvalidate()
        }

        fun setSelectionMode(active: Boolean) {
            inSelectionMode = active
            if (!active) { selDir = 0; selProgress = 0f }
            postInvalidate()
        }

        fun setSelectionDir(dir: Int, progress: Float) {
            selDir = dir
            selProgress = progress.coerceIn(0f, 1f)
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f

            // Selection mode: draw 4-direction labels with casting progress
            if (inSelectionMode) {
                drawSelectionOverlay(canvas, cx, cy)
                // Still draw the center cursor dot
                canvas.drawCircle(cx, cy, innerR, outlinePaint)
                canvas.drawCircle(cx, cy, innerR, ringPaint)
                canvas.drawCircle(cx, cy, 3.5f, dotOutlinePaint)
                canvas.drawCircle(cx, cy, 2.5f, dotPaint)
                return
            }

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

            // Dwell mode indicator: small dot to the left of cursor
            if (dwellMode) {
                val dx = cx - 14f
                for (paint in arrayOf(scrollArrowBgPaint, scrollArrowPaint)) {
                    canvas.drawCircle(dx, cy, 2.5f, paint)
                }
            }

            // Scroll mode indicator: 4-direction arrows, active one big + bright
            if (scrollMode) {
                val offset = 14f
                val aH = 6f    // arrow head height
                val aW = 4f    // arrow head half-width

                fun drawArrow(tipX: Float, tipY: Float, b1X: Float, b1Y: Float, b2X: Float, b2Y: Float, active: Boolean) {
                    val bgP = if (active) outlinePaint else scrollArrowBgPaint
                    val fgP = if (active) dwellFillPaint else scrollArrowPaint
                    canvas.drawLine(tipX, tipY, b1X, b1Y, bgP)
                    canvas.drawLine(tipX, tipY, b2X, b2Y, bgP)
                    canvas.drawLine(tipX, tipY, b1X, b1Y, fgP)
                    canvas.drawLine(tipX, tipY, b2X, b2Y, fgP)
                    // Active: draw shaft line for emphasis
                    if (active) {
                        val shaftX = (b1X + b2X) / 2f
                        val shaftY = (b1Y + b2Y) / 2f
                        val tailX = shaftX + (shaftX - tipX) * 0.6f
                        val tailY = shaftY + (shaftY - tipY) * 0.6f
                        canvas.drawLine(tipX, tipY, tailX, tailY, bgP)
                        canvas.drawLine(tipX, tipY, tailX, tailY, fgP)
                    }
                }

                // Up
                drawArrow(cx, cy - offset - aH, cx - aW, cy - offset, cx + aW, cy - offset, scrollDirY < 0)
                // Down
                drawArrow(cx, cy + offset + aH, cx - aW, cy + offset, cx + aW, cy + offset, scrollDirY > 0)
                // Left
                drawArrow(cx - offset - aH, cy, cx - offset, cy - aW, cx - offset, cy + aW, scrollDirX < 0)
                // Right
                drawArrow(cx + offset + aH, cy, cx + offset, cy - aW, cx + offset, cy + aW, scrollDirX > 0)
            }
        }

        private fun drawSelectionOverlay(canvas: Canvas, cx: Float, cy: Float) {
            // Dark semi-transparent background circle for readability
            val bgCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(240, 0, 0, 0)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, selRadius + 22f, bgCirclePaint)

            // Outer ring
            val ringPaintSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(80, 0, 255, 0)
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
            }
            canvas.drawCircle(cx, cy, selRadius + 22f, ringPaintSel)

            // Labels for 4 directions: 1=up(OFF), 2=down(EXIT), 3=left(SCROLL), 4=right(DWELL)
            data class DirInfo(val dir: Int, val label: String, val lx: Float, val ly: Float)

            val dirs = listOf(
                DirInfo(1, "OFF",    cx,              cy - selRadius + 2f),
                DirInfo(2, "EXIT",   cx,              cy + selRadius + 8f),
                DirInfo(3, "SCR",    cx - selRadius,   cy + 6f),
                DirInfo(4, "DWL",    cx + selRadius,   cy + 6f)
            )

            for (d in dirs) {
                val active = selDir == d.dir
                val paint = if (active) selLabelPaint else selLabelDimPaint
                val bgPaint = selLabelBgPaint.apply {
                    textSize = paint.textSize
                }
                // Text outline for readability
                canvas.drawText(d.label, d.lx, d.ly, bgPaint)
                canvas.drawText(d.label, d.lx, d.ly, paint)

                // If this direction is active, draw casting progress arc around label
                if (active && selProgress > 0f) {
                    val arcR = 20f
                    arcRect.set(d.lx - arcR, d.ly - arcR - 6f, d.lx + arcR, d.ly + arcR - 6f)
                    canvas.drawArc(arcRect, -90f, 360f, false, selArcBgPaint)
                    canvas.drawArc(arcRect, -90f, 360f * selProgress, false, selArcFillPaint)
                }
            }

            // Cross-hair lines from center toward each direction
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(50, 0, 255, 0)
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            val lineInner = 14f
            val lineOuter = 30f
            canvas.drawLine(cx, cy - lineInner, cx, cy - lineOuter, linePaint)
            canvas.drawLine(cx, cy + lineInner, cx, cy + lineOuter, linePaint)
            canvas.drawLine(cx - lineInner, cy, cx - lineOuter, cy, linePaint)
            canvas.drawLine(cx + lineInner, cy, cx + lineOuter, cy, linePaint)
        }
    }
}
