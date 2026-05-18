package com.cortex.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
            val token = prefs.getString("google_id_token", null)
            val name = prefs.getString("user_name", "Antoine") // On récupère le nom sauvegardé
            
            val recentNotifs = NotificationService.getRecentNotifications()
            val prompt = "Génère un briefing matinal complet pour $name en analysant ces notifications récentes : $recentNotifs. Sois poli et efficace comme un majordome britannique."
            
            // Appel API Jarvis (avec le jeton pour accéder au Calendrier/Gmail)
            val response = JarvisApiClient.apiService.sendMessage(ChatRequest(prompt, token, name))
            val briefing = response.response ?: response.text ?: "Impossible de générer le briefing."

            showNotification("☕ Ton Briefing Jarvis, $name", briefing)
            
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "cortex_briefing"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Morning Briefing", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        // Création de l'intent pour ouvrir l'application au clic
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 
            0, 
            intent, 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(message)
                .setBigContentTitle(title)
                .setSummaryText("Briefing Jarvis"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()


        notificationManager.notify(1001, notification)
    }
}
