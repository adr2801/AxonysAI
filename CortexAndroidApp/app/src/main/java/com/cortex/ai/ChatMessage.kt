package com.cortex.ai

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: String? = null
)
