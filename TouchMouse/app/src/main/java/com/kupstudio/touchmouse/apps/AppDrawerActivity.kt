package com.kupstudio.touchmouse.apps

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kupstudio.touchmouse.R
import com.kupstudio.touchmouse.service.TouchMouseService

/**
 * Shows favorite apps for quick launch. Opened on awake gesture.
 */
class AppDrawerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvTitle: TextView
    private var apps = listOf<AppInfo>()
    private var selectedIdx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.solid_black))
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        tvTitle = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.hud_cyan_dim))
            textSize = 10f
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(tvTitle)
        root.addView(divider())

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(container)

        root.addView(divider())
        root.addView(TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.hud_green_dim))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            text = "Swipe: Navigate    Tap: Launch    Back: Close"
        })

        setContentView(root)

        apps = FavoriteAppsManager.getFavoriteApps(this)
        if (apps.isEmpty()) {
            apps = FavoriteAppsManager.getLaunchableApps(this)
            tvTitle.text = "ALL APPS (no favorites)"
        }
        selectedIdx = 0
        render()
    }

    override fun onResume() {
        super.onResume()
        TouchMouseService.instance?.appInForeground = true
    }

    override fun onPause() {
        super.onPause()
        TouchMouseService.instance?.appInForeground = false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (apps.isNotEmpty()) {
                    selectedIdx = (selectedIdx - 1 + apps.size) % apps.size
                    render()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (apps.isNotEmpty()) {
                    selectedIdx = (selectedIdx + 1) % apps.size
                    render()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (apps.isNotEmpty()) launchApp(apps[selectedIdx])
                return true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun launchApp(app: AppInfo) {
        val intent = if (app.key.startsWith("activity:")) {
            Intent().apply {
                component = ComponentName.unflattenFromString(app.key.removePrefix("activity:"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            packageManager.getLaunchIntentForPackage(app.key)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (intent != null) {
            startActivity(intent)
            finish()
        }
    }

    private fun render() {
        container.removeAllViews()
        val bright = getColor(R.color.hud_green_bright)
        val dim = getColor(R.color.hud_green_dim)
        val highlight = getColor(R.color.row_highlight)

        val windowSize = 8
        val startIdx = (selectedIdx - windowSize / 2).coerceIn(0, (apps.size - windowSize).coerceAtLeast(0))
        val endIdx = (startIdx + windowSize).coerceAtMost(apps.size)

        for (i in startIdx until endIdx) {
            val app = apps[i]
            val selected = i == selectedIdx
            val appRef = app
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(if (selected) highlight else 0)
                isClickable = true
                isFocusable = false
                setOnClickListener { launchApp(appRef) }
            }

            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
                if (app.icon != null) setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })

            row.addView(TextView(this).apply {
                text = "${if (selected) "> " else "  "}${app.label}"
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                setTextColor(if (selected) bright else dim)
            })

            container.addView(row)
        }

        tvTitle.text = if (apps.isEmpty()) "NO APPS" else "APPS  (${selectedIdx + 1}/${apps.size})"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(getColor(R.color.hud_cyan_dim))
        alpha = 0.5f
    }
}
