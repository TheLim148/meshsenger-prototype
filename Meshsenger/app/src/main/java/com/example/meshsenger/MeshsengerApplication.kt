package com.example.meshsenger

import android.app.Application
import com.example.meshsenger.mesh.logging.MeshLogger

class MeshsengerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MeshLogger.install(this)
        MeshLogger.info("MeshsengerApplication", "Application запущен. Persistent debug logs enabled")
    }
}
