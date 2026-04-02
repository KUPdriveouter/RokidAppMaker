package com.kupstudio.touchmouse.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Shows a small help text overlay at the bottom of the screen.
 * Used to display current mode name and how to exit.
 */
class HelpOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var textView: TextView? = null
    private var isShowing = false

    fun show(title: String, hint: String) {
        mainHandler.post {
            val text = "[ $title ]  $hint"
            if (isShowing) {
                textView?.text = text
                return@post
            }
            try {
                val tv = TextView(context).apply {
                    this.text = text
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.GREEN)
                    setBackgroundColor(Color.argb(160, 0, 0, 0))
                    setPadding(16, 4, 16, 4)
                    gravity = Gravity.CENTER
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = 8
                }
                windowManager.addView(tv, params)
                textView = tv
                isShowing = true
            } catch (e: Exception) {
                com.kupstudio.touchmouse.util.DebugLog.e("HelpOverlay", "show: ${e.message}")
            }
        }
    }

    fun hide() {
        if (!isShowing) return
        mainHandler.post {
            try { textView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            textView = null
            isShowing = false
        }
    }
}
