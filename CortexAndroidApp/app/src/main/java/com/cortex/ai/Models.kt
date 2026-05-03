package com.cortex.ai

import com.google.gson.annotations.SerializedName

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class CortexMessage(
    val text: String,
    val isUser: Boolean,
    val isError: Boolean = false
)

data class TaskItem(
    val name: String,
    val score: Double
)

data class CortexRequest(
    val prompt: String,
    @SerializedName("google_token") val googleToken: String? = null,
    @SerializedName("user_name") val userName: String? = "Antoine",
    val lat: Double? = null,
    val lng: Double? = null,
    @SerializedName("thread_id") val threadId: String? = "main"
)

data class CortexResponse(
    val response: String? = null,
    val text: String? = null
)

data class ThreadResponse(
    val threads: List<String>
)

data class JarvisNotification(
    val title: String,
    val message: String,
    val timestamp: String
)

data class NotificationResponse(
    val notifications: List<JarvisNotification>
)

data class CortexHistoryResponse(
    val history: List<CortexMessage>
)
