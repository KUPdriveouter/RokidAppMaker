package com.kupstudio.btmanager

import android.app.Application
import com.kupstudio.btmanager.util.DebugLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.i("App", "BT Manager started")
    }
}
