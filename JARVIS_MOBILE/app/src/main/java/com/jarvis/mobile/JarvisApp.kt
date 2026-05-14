package com.jarvis.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.multidex.MultiDex
import com.jarvis.mobile.memory.MemoryEngine
import com.jarvis.mobile.agent.CommandEngine

class JarvisApp : Application() {

    companion object {
        lateinit var instance: JarvisApp
            private set
        const val NOTIF_CHANNEL_ID   = "jarvis_main"
        const val NOTIF_CHANNEL_WAKE = "jarvis_wake"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        MultiDex.install(this)
        createNotificationChannels()
        MemoryEngine.init(this)
        CommandEngine.init(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "JARVIS",
                    NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "JARVIS Assistant"
                }
            )
            mgr.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_WAKE, "JARVIS Wake",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Always-on listener"
                }
            )
        }
    }
}
