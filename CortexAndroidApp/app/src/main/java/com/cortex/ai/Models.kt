package com.cortex.ai

// Modèles de données communs

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class JarvisChatMessage(val text: String, val isUser: Boolean, val isError: Boolean = false)

data class TaskItem(val name: String, val score: Double)

data class ThreadResponse(val threads: List<String>)

data class JarvisNotification(val title: String, val message: String, val timestamp: String)

data class NotificationResponse(val notifications: List<JarvisNotification>)
