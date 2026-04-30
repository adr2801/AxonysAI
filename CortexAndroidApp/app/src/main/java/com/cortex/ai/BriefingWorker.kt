package com.cortex.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val recentNotifs = NotificationService.getRecentNotifications()
            val prompt = "Generate a comprehensive morning briefing for Antoine by analyzing these recent notifications: $recentNotifs"
            
            // Appel API Jarvis (qui déclenchera le CrewAI Morning Briefing)
            val response = JarvisApiClient.apiService.sendMessage(ChatRequest(prompt))
            val briefing = response.response ?: response.text ?: "Impossible de générer le briefing."

            showNotification("Ton Briefing Jarvis ☕", briefing)
            
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "cortex_briefing"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Morning Briefing", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
