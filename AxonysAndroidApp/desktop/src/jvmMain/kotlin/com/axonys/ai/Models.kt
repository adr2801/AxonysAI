package com.axonys.ai

// Modèles de données communs (copie locale pour le desktop)
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

data class JarvisChatMessage(
    val text: String,
    val isUser: Boolean,
    val isError: Boolean = false,
    val isThinking: Boolean = false,
    val isToolRunning: Boolean = false,
    val currentTool: String? = null,
    val isNew: Boolean = false,
    val imageResult: String? = null
)

data class TaskItem(
    val id: Int? = null,
    val name: String? = "Sans titre",
    val score: Double? = 0.0,
    val status: String? = "pending"
)

data class ThreadResponse(val threads: List<String>)
data class JarvisNotification(val title: String, val message: String, val timestamp: String)
data class NotificationResponse(val notifications: List<JarvisNotification>)