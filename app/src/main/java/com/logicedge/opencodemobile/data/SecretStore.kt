package com.logicedge.opencodemobile.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SecretStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "opencode_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun get(profileId: String): String? = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(secretKey(profileId), null) }.getOrNull()
    }

    suspend fun set(profileId: String, secret: String) = withContext(Dispatchers.IO) {
        // commit(), not apply(): callers save-then-connect, and must read back
        // the fresh secret instead of racing the async write.
        prefs.edit().putString(secretKey(profileId), secret).commit()
    }

    suspend fun delete(profileId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(secretKey(profileId)).commit()
    }

    private fun secretKey(profileId: String) = "opencode_pw_$profileId"
}
