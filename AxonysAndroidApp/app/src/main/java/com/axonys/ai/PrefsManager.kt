package com.axonys.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object PrefsManager {
    private const val PREFS_NAME = "AxonysSecurePrefs"
    private const val OLD_PREFS_NAME = "AxonysPrefs"

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getUnencryptedPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Migrates sensitive data from unencrypted to encrypted prefs if necessary.
     */
    fun migrateIfNeeded(context: Context) {
        val oldPrefs = getUnencryptedPrefs(context)
        val newPrefs = getEncryptedPrefs(context)

        val sensitiveKeys = listOf("google_id_token", "user_id", "user_name")
        
        val editor = newPrefs.edit()
        var changed = false
        
        sensitiveKeys.forEach { key ->
            if (oldPrefs.contains(key) && !newPrefs.contains(key)) {
                val value = oldPrefs.getString(key, null)
                editor.putString(key, value)
                changed = true
            }
        }
        
        if (changed) {
            editor.apply()
            // We could remove from old prefs, but keeping for safety in this version
        }
    }
}
