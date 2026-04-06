package com.kupstudio.touchmouse.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import org.json.JSONArray

data class AppInfo(
    val key: String,          // packageName or "activity:component"
    val label: String,
    val icon: Drawable? = null,
    val isFavorite: Boolean = false,
    val isBuiltin: Boolean = false
)

object FavoriteAppsManager {
    private const val PREFS = "favorite_apps"
    private const val KEY_ORDERED = "favorites_ordered"

    // Launcher built-in activities
    private val LAUNCHER_BUILTINS = listOf(
        "activity:com.rokid.os.sprite.launcher/.page.camera.CameraPageActivity" to "Camera",
        "activity:com.rokid.os.sprite.launcher/.page.audio.AudioPageActivity" to "Audio Recorder",
        "activity:com.rokid.os.sprite.launcher/.page.translate.TranslatePageActivity" to "Translate",
        "activity:com.rokid.os.sprite.launcher/.page.chat.ChatPageActivity" to "AI Chat",
        "activity:com.rokid.os.sprite.launcher/.page.navigation.NavigationPageActivity" to "Navigation",
        "activity:com.rokid.os.sprite.launcher/.page.music.MusicPageActivity" to "Music",
        "activity:com.rokid.os.sprite.launcher/.page.live.LivePageActivity" to "Live",
    )

    // ── Migration from old StringSet format ──

    fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ORDERED)) return // already migrated
        val old = prefs.getStringSet("favorites", null)
        if (!old.isNullOrEmpty()) {
            saveOrderedFavorites(context, old.toList())
            prefs.edit().remove("favorites").apply()
        }
    }

    // ── Ordered favorites ──

    fun getOrderedFavorites(context: Context): List<String> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDERED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveOrderedFavorites(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDERED, arr.toString()).apply()
    }

    fun addFavorite(context: Context, key: String) {
        val list = getOrderedFavorites(context).toMutableList()
        if (!list.contains(key)) list.add(key)
        saveOrderedFavorites(context, list)
    }

    fun removeFavorite(context: Context, key: String) {
        val list = getOrderedFavorites(context).toMutableList()
        list.remove(key)
        saveOrderedFavorites(context, list)
    }

    fun isFavorite(context: Context, key: String): Boolean {
        return getOrderedFavorites(context).contains(key)
    }

    fun moveFavorite(context: Context, key: String, delta: Int) {
        val list = getOrderedFavorites(context).toMutableList()
        val idx = list.indexOf(key)
        if (idx < 0) return
        val newIdx = (idx + delta).coerceIn(0, list.size - 1)
        if (newIdx == idx) return
        list.removeAt(idx)
        list.add(newIdx, key)
        saveOrderedFavorites(context, list)
    }

    // ── App queries ──

    fun getLaunchableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val favKeys = getOrderedFavorites(context).toSet()
        val apps = mutableListOf<AppInfo>()

        // Launcher built-in activities (sorted first)
        for ((key, label) in LAUNCHER_BUILTINS) {
            val icon = try { pm.getApplicationIcon("com.rokid.os.sprite.launcher") } catch (_: Exception) { null }
            apps.add(AppInfo(key, label, icon, favKeys.contains(key), isBuiltin = true))
        }

        // Regular launchable apps
        pm.getInstalledPackages(0)
            .filter { pkg -> pm.getLaunchIntentForPackage(pkg.packageName) != null }
            .forEach { pkg ->
                if (pkg.packageName != context.packageName) {
                    val label = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName
                    val icon = try { pkg.applicationInfo?.loadIcon(pm) } catch (_: Exception) { null }
                    apps.add(AppInfo(pkg.packageName, label, icon, favKeys.contains(pkg.packageName), isBuiltin = false))
                }
            }

        // Built-ins first, then alphabetical
        return apps.sortedWith(compareByDescending<AppInfo> { it.isBuiltin }.thenBy { it.label.lowercase() })
    }

    fun getFavoriteApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val ordered = getOrderedFavorites(context)
        if (ordered.isEmpty()) return emptyList()
        val builtinMap = LAUNCHER_BUILTINS.toMap()
        return ordered.mapNotNull { key ->
            if (key.startsWith("activity:")) {
                val label = builtinMap[key] ?: key
                val icon = try { pm.getApplicationIcon("com.rokid.os.sprite.launcher") } catch (_: Exception) { null }
                AppInfo(key, label, icon, true, isBuiltin = true)
            } else {
                try {
                    val ai = pm.getApplicationInfo(key, 0)
                    if (pm.getLaunchIntentForPackage(key) != null) {
                        AppInfo(key, ai.loadLabel(pm).toString(), ai.loadIcon(pm), true)
                    } else null
                } catch (_: Exception) { null }
            }
        }
    }

    // Migrate old Set-based favorites
    fun getFavorites(context: Context): Set<String> {
        return getOrderedFavorites(context).toSet()
    }
}
