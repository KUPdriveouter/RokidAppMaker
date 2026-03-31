package com.kupstudio.touchmouse.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
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
        private const val CURSOR_SIZE = 20
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

        fun setFlash(on: Boolean) {
            flashing = on
            invalidate()
        }

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
}
