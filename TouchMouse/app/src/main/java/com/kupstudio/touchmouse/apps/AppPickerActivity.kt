package com.kupstudio.touchmouse.apps

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kupstudio.touchmouse.R
import com.kupstudio.touchmouse.service.TouchMouseService

class AppPickerActivity : AppCompatActivity() {

    private enum class Section { FAVS, APPS }

    private lateinit var favsTitle: TextView
    private lateinit var favsContainer: LinearLayout
    private lateinit var favsScroll: ScrollView
    private lateinit var appsTitle: TextView
    private lateinit var appsContainer: LinearLayout
    private lateinit var appsScroll: ScrollView
    private lateinit var tvHint: TextView

    private var allApps = listOf<AppInfo>()
    private var favApps = listOf<AppInfo>()
    private var section = Section.APPS
    private var favsIdx = 0
    private var appsIdx = 0
    private var grabbing = false  // reorder mode in FAVS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.solid_black))
        }

        // ── Top: Favorites ──
        favsTitle = sectionTitle("FAVORITES")
        root.addView(favsTitle)
        root.addView(divider())

        favsScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP.MATCH, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        favsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP.MATCH, LP.WRAP)
        }
        favsScroll.addView(favsContainer)
        root.addView(favsScroll)

        root.addView(divider())

        // ── Bottom: All Apps ──
        appsTitle = sectionTitle("ALL APPS")
        root.addView(appsTitle)
        root.addView(divider())

        appsScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP.MATCH, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP.MATCH, LP.WRAP)
        }
        appsScroll.addView(appsContainer)
        root.addView(appsScroll)

        root.addView(divider())

        // ── Hint ──
        tvHint = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.hud_green_dim))
            textSize = 9f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(tvHint)

        setContentView(root)
        reload()
    }

    override fun onResume() {
        super.onResume()
        TouchMouseService.instance?.appInForeground = true
    }

    override fun onPause() {
        super.onPause()
        TouchMouseService.instance?.appInForeground = false
    }

    private fun reload() {
        allApps = FavoriteAppsManager.getLaunchableApps(this)
        favApps = FavoriteAppsManager.getFavoriteApps(this)
        favsIdx = favsIdx.coerceIn(0, (favApps.size - 1).coerceAtLeast(0))
        appsIdx = appsIdx.coerceIn(0, (allApps.size - 1).coerceAtLeast(0))
        if (favApps.isEmpty() && section == Section.FAVS) {
            section = Section.APPS
            grabbing = false
        }
        render()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveUp(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveDown(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onSelect(); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (grabbing) { grabbing = false; render(); return true }
                finish(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveUp() {
        if (grabbing && section == Section.FAVS) {
            // Move grabbed item up
            if (favsIdx > 0) {
                FavoriteAppsManager.moveFavorite(this, favApps[favsIdx].key, -1)
                favsIdx--
                favApps = FavoriteAppsManager.getFavoriteApps(this)
                render()
            }
            return
        }
        when (section) {
            Section.FAVS -> {
                if (favsIdx > 0) favsIdx--
            }
            Section.APPS -> {
                if (appsIdx > 0) appsIdx--
                else if (favApps.isNotEmpty()) {
                    section = Section.FAVS
                    favsIdx = favApps.size - 1
                }
            }
        }
        render()
    }

    private fun moveDown() {
        if (grabbing && section == Section.FAVS) {
            // Move grabbed item down
            if (favsIdx < favApps.size - 1) {
                FavoriteAppsManager.moveFavorite(this, favApps[favsIdx].key, 1)
                favsIdx++
                favApps = FavoriteAppsManager.getFavoriteApps(this)
                render()
            }
            return
        }
        when (section) {
            Section.FAVS -> {
                if (favsIdx < favApps.size - 1) favsIdx++
                else { section = Section.APPS; appsIdx = 0 }
            }
            Section.APPS -> {
                if (appsIdx < allApps.size - 1) appsIdx++
            }
        }
        render()
    }

    private fun onSelect() {
        when (section) {
            Section.FAVS -> {
                // Toggle grab mode for reordering
                grabbing = !grabbing
                render()
            }
            Section.APPS -> {
                if (allApps.isEmpty()) return
                toggleFavorite(allApps[appsIdx].key)
            }
        }
    }

    private fun toggleFavorite(key: String) {
        if (FavoriteAppsManager.isFavorite(this, key)) {
            FavoriteAppsManager.removeFavorite(this, key)
        } else {
            FavoriteAppsManager.addFavorite(this, key)
        }
        reload()
    }

    // ── Render ──

    private fun render() {
        val bright = getColor(R.color.hud_green_bright)
        val dim = getColor(R.color.hud_green_dim)
        val cyan = getColor(R.color.hud_cyan)
        val cyanDim = getColor(R.color.hud_cyan_dim)
        val highlight = getColor(R.color.row_highlight)
        val isFavsActive = section == Section.FAVS

        favsTitle.text = "FAVORITES (${favApps.size})"
        favsTitle.setTextColor(if (isFavsActive) cyan else cyanDim)

        appsTitle.text = "ALL APPS (${allApps.size})"
        appsTitle.setTextColor(if (!isFavsActive) cyan else cyanDim)

        tvHint.text = when {
            grabbing -> "Swipe: Move    Tap: Drop    Back: Cancel"
            section == Section.FAVS -> "Tap: Grab to reorder    Back: Done"
            else -> "Tap/Click: Toggle fav    Back: Done"
        }

        // Favorites section
        favsContainer.removeAllViews()
        if (favApps.isEmpty()) {
            favsContainer.addView(TextView(this).apply {
                text = "  (empty)"
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 10f
                setTextColor(dim)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            })
        } else {
            for ((i, app) in favApps.withIndex()) {
                val selected = isFavsActive && i == favsIdx
                val isGrabbed = selected && grabbing
                val color = when {
                    isGrabbed -> getColor(R.color.hud_cyan)
                    selected -> bright
                    else -> cyan
                }
                val prefix = when {
                    isGrabbed -> "= "
                    selected -> "> "
                    else -> "  "
                }
                val row = makeAppRow(app, prefix, selected, color, highlight)
                favsContainer.addView(row)
                if (selected) row.post {
                    favsScroll.smoothScrollTo(0, row.top - favsScroll.height / 3)
                }
            }
        }

        // All Apps section
        appsContainer.removeAllViews()
        for ((i, app) in allApps.withIndex()) {
            val selected = !isFavsActive && i == appsIdx
            val color = when {
                selected -> bright
                app.isBuiltin -> cyanDim
                else -> dim
            }
            val tag = when {
                app.isBuiltin && app.isFavorite -> "[B]* "
                app.isBuiltin -> "[B]  "
                app.isFavorite -> "  *  "
                else -> "     "
            }
            val prefix = if (selected) "> " else "  "
            val row = makeAppRow(app, "$prefix$tag", selected, color, highlight,
                clickable = true, clickKey = app.key)
            appsContainer.addView(row)
            if (selected) row.post {
                appsScroll.smoothScrollTo(0, row.top - appsScroll.height / 3)
            }
        }
    }

    private fun makeAppRow(
        app: AppInfo, prefix: String, selected: Boolean,
        textColor: Int, highlight: Int,
        clickable: Boolean = false, clickKey: String? = null
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(if (selected) highlight else 0)

            if (clickable && clickKey != null) {
                isClickable = true
                isFocusable = false
                setOnClickListener { toggleFavorite(clickKey) }
            }

            addView(ImageView(this@AppPickerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) }
                if (app.icon != null) setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })

            addView(TextView(this@AppPickerActivity).apply {
                text = "$prefix${app.label}"
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(LP.MATCH, LP.WRAP)
            })
        }
    }

    // ── Helpers ──

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LP.MATCH, 1)
        setBackgroundColor(getColor(R.color.hud_cyan_dim))
        alpha = 0.5f
    }
    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        typeface = android.graphics.Typeface.MONOSPACE
        setTextColor(getColor(R.color.hud_cyan_dim))
        textSize = 9f
        letterSpacing = 0.15f
        setPadding(dp(10), dp(3), dp(10), dp(2))
    }

    private object LP {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
