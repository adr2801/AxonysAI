package com.cortex.ai

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// Modèles de données (On ne garde que ceux spécifiques à GitHub ici)
data class ChatRequest(
    val prompt: String, 
    val google_token: String? = null, 
    val user_name: String? = "Antoine",
    val lat: Double? = null,
    val lng: Double? = null,
    val thread_id: String? = "main"
)
data class ChatResponse(val response: String?, val text: String?)
data class GithubRelease(val tag_name: String, val html_url: String)
data class HistoryItem(val text: String, val isUser: Boolean)
data class HistoryResponse(val history: List<HistoryItem>)


// Interfaces Retrofit
interface GithubApiService {
    @GET("repos/adr2801/CortexAI/releases/latest")
    suspend fun getLatestRelease(): GithubRelease
}

// Les autres modèles (ThreadResponse, NotificationResponse) sont dans Models.kt

interface JarvisApiService {
    @POST("/chat")
    suspend fun sendMessage(@Body request: ChatRequest): ChatResponse

    @POST("/anticipate")
    suspend fun anticipate(@Body request: ChatRequest): ChatResponse

    @GET("/threads")
    suspend fun getThreads(): ThreadResponse

    @GET("/notifications")
    suspend fun getNotifications(): NotificationResponse

    @POST("/notifications/clear")
    suspend fun clearNotifications(): Any

    @GET("/history/{thread_id}")
    suspend fun getHistory(
        @Path("thread_id") threadId: String,
        @retrofit2.http.Query("user_id") userId: String
    ): HistoryResponse

}


// Singleton pour fournir l'API
object JarvisApiClient {
    private const val BASE_URL = "https://addrr-cortex-ai.hf.space/"

    private val okHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val apiService: JarvisApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
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
