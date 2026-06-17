package com.axonys.ai

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

class NotificationService : NotificationListenerService() {

    companion object {
        private val notificationLogs = CopyOnWriteArrayList<String>()

        fun getRecentNotifications(): String {
            return if (notificationLogs.isEmpty()) "Aucune notification récente."
            else notificationLogs.takeLast(10).joinToString("\n")
        }
        
        fun clearLogs() {
            notificationLogs.clear()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val title = sbn.notification.extras.getString("android.title") ?: ""
        val text = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: ""
        
        if (text.isNotBlank() && !packageName.contains("com.axonys.ai")) {
            val logEntry = "[$packageName] $title: $text"
            notificationLogs.add(logEntry)
            if (notificationLogs.size > 50) notificationLogs.removeAt(0)
            Log.d("AxonysNotif", "Capture: $logEntry")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optionnel
    }
}
