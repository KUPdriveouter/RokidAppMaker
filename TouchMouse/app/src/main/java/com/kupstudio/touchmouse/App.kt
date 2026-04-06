package com.kupstudio.touchmouse

import android.app.Application
import com.kupstudio.touchmouse.apps.FavoriteAppsManager
import com.kupstudio.touchmouse.util.DebugLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FavoriteAppsManager.migrateIfNeeded(this)
        DebugLog.i("App", "GazeMou started")
    }
}
