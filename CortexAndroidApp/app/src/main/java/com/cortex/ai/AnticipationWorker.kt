package com.cortex.ai

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnticipationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
            val token = prefs.getString("google_id_token", null)
            val name = prefs.getString("user_name", "Antoine")
            
            // Note: Le worker n'a pas accès direct aux variables de MainActivity, 
            // on pourrait stocker lat/lng dans les prefs si on voulait être ultra précis ici.
            // Pour l'instant, Jarvis utilisera sa dernière position connue ou fera sans.
            
            Log.d("JarvisAnticipate", "Déclenchement de la réflexion proactive...")
            
            val response = JarvisApiClient.apiService.anticipate(ChatRequest(
                prompt = "Analyse proactive",
                google_token = token,
                user_name = name
            ))
            
            Log.d("JarvisAnticipate", "Analyse terminée: ${response.response ?: "Ok"}")
            
            Result.success()
        } catch (e: Exception) {
            Log.e("JarvisAnticipate", "Erreur lors de l'anticipation: ${e.message}")
            Result.retry()
        }
    }
}
