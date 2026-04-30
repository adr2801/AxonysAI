package com.cortex.ai

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// Modèles de données
data class ChatRequest(val prompt: String)
data class ChatResponse(val response: String?, val text: String?)

// Interface Retrofit
interface JarvisApiService {
    @POST("chat")
    suspend fun sendMessage(@Body request: ChatRequest): ChatResponse
}

// Singleton pour fournir l'API
object JarvisApiClient {
    private const val BASE_URL = "https://addrr-cortex-ai.hf.space/"

    val apiService: JarvisApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JarvisApiService::class.java)
    }
}
