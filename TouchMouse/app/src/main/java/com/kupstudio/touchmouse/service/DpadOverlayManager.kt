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
 * Draws two overlays:
 * 1. A small D-pad selector widget in the bottom-right corner
 * 2. A cursor crosshair at the current cursor position
 */
class DpadOverlayManager(private val context: Context) {

    enum class DpadButton { UP, RIGHT, DOWN, LEFT, CLICK }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var dpadView: DpadView? = null
    private var cursorView: CursorDotView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var isShowing = false

    private var selected = DpadButton.UP

    companion object {
        private const val DPAD_SIZE = 100   // px — widget size
        private const val CURSOR_SIZE = 20  // px — cursor dot size
        private const val MARGIN = 8        // px — margin from screen edge

        val CYCLE = listOf(
            DpadButton.UP,
            DpadButton.RIGHT,
            DpadButton.DOWN,
            DpadButton.LEFT,
            DpadButton.CLICK
        )
    }

    fun show(cursorX: Float, cursorY: Float) {
        if (isShowing) return
        mainHandler.post {
            try {
                // D-pad widget — bottom right corner
                val dpad = DpadView(context, DPAD_SIZE)
                dpad.setSelected(selected)
                val dpadParams = WindowManager.LayoutParams(
                    DPAD_SIZE, DPAD_SIZE,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = MARGIN
                    y = MARGIN
                }
                windowManager.addView(dpad, dpadParams)
                dpadView = dpad

                // Cursor dot
                val cursor = CursorDotView(context, CURSOR_SIZE)
                val cParams = WindowManager.LayoutParams(
                    CURSOR_SIZE, CURSOR_SIZE,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (cursorX - CURSOR_SIZE / 2).toInt()
                    y = (cursorY - CURSOR_SIZE / 2).toInt()
                }
                windowManager.addView(cursor, cParams)
                cursorView = cursor
                cursorParams = cParams

                isShowing = true
            } catch (e: Exception) {
                com.kupstudio.touchmouse.util.DebugLog.e("DpadOverlay", "show: ${e.message}")
            }
        }
    }

    fun hide() {
        if (!isShowing) return
        mainHandler.post {
            try { dpadView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            try { cursorView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            dpadView = null
            cursorView = null
            cursorParams = null
            isShowing = false
        }
    }

    fun getSelected(): DpadButton = selected

    fun selectNext() {
        val idx = CYCLE.indexOf(selected)
        selected = CYCLE[(idx + 1) % CYCLE.size]
        mainHandler.post { dpadView?.setSelected(selected) }
    }

    fun selectPrev() {
        val idx = CYCLE.indexOf(selected)
        selected = CYCLE[(idx - 1 + CYCLE.size) % CYCLE.size]
        mainHandler.post { dpadView?.setSelected(selected) }
    }

    fun updateCursorPosition(x: Float, y: Float) {
        val view = cursorView ?: return
        val params = cursorParams ?: return
        params.x = (x - CURSOR_SIZE / 2).toInt()
        params.y = (y - CURSOR_SIZE / 2).toInt()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    fun flashClick() {
        mainHandler.post {
            cursorView?.flash()
            mainHandler.postDelayed({ cursorView?.unflash() }, 120)
        }
    }

    fun flashMove() {
        mainHandler.post {
            dpadView?.flashSelected()
            mainHandler.postDelayed({ dpadView?.unflash() }, 80)
        }
    }

    // ── Cursor dot view ──────────────────────────────────────────

    private class CursorDotView(context: Context, private val sz: Int) : View(context) {

        private var flashing = false

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            alpha = 160
        }

        fun flash() { flashing = true; invalidate() }
        fun unflash() { flashing = false; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            if (flashing) {
                canvas.drawCircle(cx, cy, width / 2f - 1, flashPaint)
            }
            canvas.drawCircle(cx, cy, width / 2f - 1, ringPaint)
            canvas.drawCircle(cx, cy, 2.5f, dotPaint)
        }
    }

    // ── D-pad widget view ────────────────────────────────────────

    private class DpadView(context: Context, private val sz: Int) : View(context) {

        private var currentSelected = DpadButton.UP
        private var isFlash = false

        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#446600")
            style = Paint.Style.FILL
        }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }
        private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22000000")
            style = Paint.Style.FILL
        }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44666600")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10f
            textAlign = Paint.Align.CENTER
        }

        fun setSelected(btn: DpadButton) {
            currentSelected = btn
            invalidate()
        }

        fun flashSelected() { isFlash = true; invalidate() }
        fun unflash() { isFlash = false; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val unit = w / 5f  // divide into 5x5 grid

            // Background
            canvas.drawRoundRect(RectF(0f, 0f, w, h), 6f, 6f, bgPaint)
            canvas.drawRoundRect(RectF(0f, 0f, w, h), 6f, 6f, outlinePaint)

            // Button rects (in the cross shape)
            val btnUp = RectF(unit * 1.5f, 0f, unit * 3.5f, unit * 1.8f)
            val btnRight = RectF(unit * 3.2f, unit * 1.5f, w, unit * 3.5f)
            val btnDown = RectF(unit * 1.5f, unit * 3.2f, unit * 3.5f, h)
            val btnLeft = RectF(0f, unit * 1.5f, unit * 1.8f, unit * 3.5f)
            val btnClick = RectF(unit * 1.5f, unit * 1.5f, unit * 3.5f, unit * 3.5f)

            drawButton(canvas, btnUp, DpadButton.UP, "^")
            drawButton(canvas, btnRight, DpadButton.RIGHT, ">")
            drawButton(canvas, btnDown, DpadButton.DOWN, "v")
            drawButton(canvas, btnLeft, DpadButton.LEFT, "<")
            drawButton(canvas, btnClick, DpadButton.CLICK, "O")
        }

        private fun drawButton(canvas: Canvas, rect: RectF, btn: DpadButton, label: String) {
            val paint = when {
                btn == currentSelected && isFlash -> flashPaint
                btn == currentSelected -> activePaint
                else -> dimPaint
            }
            canvas.drawRoundRect(rect, 4f, 4f, paint)

            // Label
            textPaint.color = if (btn == currentSelected) Color.BLACK else Color.parseColor("#88888800")
            textPaint.textSize = rect.height() * 0.45f
            val textY = rect.centerY() + textPaint.textSize * 0.35f
            canvas.drawText(label, rect.centerX(), textY, textPaint)
        }
    }
}
