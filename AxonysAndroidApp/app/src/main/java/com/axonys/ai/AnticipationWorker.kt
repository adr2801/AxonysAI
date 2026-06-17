package com.axonys.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnticipationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val channelId = "jarvis_anticipation"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = PrefsManager.getEncryptedPrefs(applicationContext)
            val token = prefs.getString("google_id_token", null)
            val userId = prefs.getString("user_id", null) ?: android.provider.Settings.Secure.getString(
                applicationContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "default_device"
            val name = prefs.getString("user_name", "Utilisateur")
            
            Log.d("JarvisAnticipate", "Déclenchement de la réflexion proactive...")
            
            val response = JarvisApiClient.apiService.anticipate(ChatRequest(
                prompt = "Analyse proactive",
                google_token = token,
                user_id = userId,
                user_name = name
            ))
            
            Log.d("JarvisAnticipate", "Analyse terminée. Status: ${response.status}. Notifications reçues: ${response.notifications?.size ?: 0}")
            
            // Si Jarvis a généré des notifications proactives
            response.notifications?.forEach { notif ->
                Log.d("JarvisAnticipate", "Envoi d'une notification locale: ${notif.title}")
                sendLocalNotification(notif.title, notif.message)
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("JarvisAnticipate", "Erreur lors de l'anticipation: ${e.message}")
            Result.retry()
        }
    }

    private fun sendLocalNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Anticipation Jarvis", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // On pourra changer l'icône plus tard
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

