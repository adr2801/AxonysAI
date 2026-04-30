package com.cortex.ai

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// Modèles de données
data class ChatRequest(val prompt: String)
data class ChatResponse(val response: String?, val text: String?)
data class GithubRelease(val tag_name: String, val html_url: String)

// Interfaces Retrofit
interface GithubApiService {
    @retrofit2.http.GET("repos/adr2801/CortexAI/releases/latest")
    suspend fun getLatestRelease(): GithubRelease
}

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

    val githubService: GithubApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GithubApiService::class.java)
    }
}
