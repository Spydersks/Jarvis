package com.jarvis.mobile.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

// ── Notification Listener ─────────────────────────────────────────────────────
class NotificationListener : NotificationListenerService() {

    companion object {
        var instance: NotificationListener? = null
    }

    private val notifications = mutableListOf<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i("NotifListener", "Connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras  = sbn.notification.extras
            val title   = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
            val text    = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val pkg     = sbn.packageName
            val entry   = "$title: $text (from $pkg)"
            notifications.add(0, entry)
            if (notifications.size > 100) notifications.removeAt(notifications.size - 1)
            Log.d("NotifListener", "Posted: $entry")
        } catch (e: Exception) {
            Log.e("NotifListener", "onPosted: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    fun getAll(): List<String> = notifications.toList()

    fun clearAll() {
        try { cancelAllNotifications() } catch (e: Exception) {}
        notifications.clear()
    }
}

// ── Device Admin ──────────────────────────────────────────────────────────────
class JarvisDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("DeviceAdmin", "JARVIS device admin enabled")
    }
    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("DeviceAdmin", "JARVIS device admin disabled")
    }
}
