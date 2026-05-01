package com.sightsense.app

import android.app.Application

class SightSenseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Smoke test removed to avoid resource contention with main pipeline during startup.
    }
}
