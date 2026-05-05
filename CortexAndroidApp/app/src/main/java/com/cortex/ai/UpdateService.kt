package com.cortex.ai

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonFactory
import com.google.gson.annotations.SerializedName

interface GitHubApi {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GitHubRelease
}

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("html_url") val url: String,
    @SerializedName("body") val description: String
)

object UpdateChecker {
    private const val GITHUB_OWNER = "TON_PSEUDO" // À MODIFIER
    private const val GITHUB_REPO = "CortexAI"    // À MODIFIER
    
    private val api = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GitHubApi::class.java)

    suspend fun checkForUpdates(currentVersion: String): GitHubRelease? {
        return try {
            val latest = api.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            // On compare les versions (ex: "1.1.0" vs "1.2.0")
            if (latest.tagName != currentVersion && latest.tagName.replace("v", "") != currentVersion) {
                latest
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
