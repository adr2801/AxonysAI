package com.cortex.ai

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Header


// Modèles de données (On ne garde que ceux spécifiques à GitHub ici)
data class ChatRequest(
    val prompt: String, 
    val google_token: String? = null, 
    val user_name: String? = "Antoine",
    val lat: Double? = null,
    val lng: Double? = null,
    val thread_id: String? = "main",
    val mode: String? = null,
    val image_base64: String? = null
)

data class ChatResponse(val response: String?, val text: String?)
data class GithubRelease(val tag_name: String, val html_url: String)
data class HistoryItem(val text: String, val isUser: Boolean)
data class HistoryResponse(val history: List<HistoryItem>)
data class MemoryFact(val fact: String, val timestamp: String)
data class MemoryResponse(val facts: List<MemoryFact>)
data class DeleteMemoryRequest(val fact: String, val user_id: String = "antoine")
data class PreferencesResponse(val preferences: Map<String, String>)
data class SetPreferenceRequest(val preference_key: String, val preference_value: String)
data class JarvisMode(val id: Int, val name: String, val instruction: String, val icon: String?, val color: String?)
data class ModeRequest(val name: String, val instruction: String, val icon: String?, val color: String?)
data class ModeResponse(val modes: List<JarvisMode>)

data class AnticipateResponse(
    val status: String,
    val notifications: List<JarvisNotification>? = null
)



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
    suspend fun anticipate(@Body request: ChatRequest): AnticipateResponse

    @GET("/modes/{user_id}")
    suspend fun getModes(@Path("user_id") userId: String): ModeResponse

    @POST("/modes/{user_id}")
    suspend fun createMode(@Path("user_id") userId: String, @Body mode: ModeRequest): ChatResponse

    @POST("/modes/{user_id}/delete")
    suspend fun deleteMode(@Path("user_id") userId: String, @Body body: Map<String, String>): ChatResponse





    @GET("/threads")
    suspend fun getThreads(@Query("user_id") userId: String): ThreadResponse

    @GET("/notifications")
    suspend fun getNotifications(@Query("user_id") userId: String): NotificationResponse

    @POST("/notifications/clear")
    suspend fun clearNotifications(@Query("user_id") userId: String): Any



    @GET("/history/{thread_id}")
    suspend fun getHistory(
        @Path("thread_id") threadId: String,
        @Query("user_id") userId: String
    ): HistoryResponse

    @GET("/memory/{user_id}")
    suspend fun getMemory(@Path("user_id") userId: String): MemoryResponse


    @POST("/memory/delete")
    suspend fun deleteMemoryFact(@Body request: DeleteMemoryRequest): Any


    @GET("/preferences/{user_id}")
    suspend fun getPreferences(@Path("user_id") userId: String): PreferencesResponse

    @POST("/preferences/{user_id}")
    suspend fun setPreference(
        @Path("user_id") userId: String,
        @Body request: SetPreferenceRequest
    ): Any
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
