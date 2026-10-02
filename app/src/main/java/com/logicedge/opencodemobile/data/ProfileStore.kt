package com.logicedge.opencodemobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

private val Context.profileDataStore by preferencesDataStore(name = "server_profiles")

class ProfileStore(private val context: Context) {

    private val key = stringPreferencesKey("opencode_server_profiles")
    private val writeMutex = Mutex()

    val profiles: Flow<List<ServerProfile>> =
        context.profileDataStore.data.map { prefs ->
            decode(prefs[key])
        }

    suspend fun load(): List<ServerProfile> = profiles.first()

    private fun decode(raw: String?): List<ServerProfile> {
        if (raw == null) return emptyList()
        return runCatching {
            OpenCodeJson.decodeFromString(ListSerializer(ServerProfile.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    private suspend fun persist(list: List<ServerProfile>) {
        val raw = OpenCodeJson.encodeToString(ListSerializer(ServerProfile.serializer()), list)
        context.profileDataStore.edit { it[key] = raw }
    }

    /**
     * All mutations run inside one DataStore transaction under a mutex, so
     * concurrent writers (e.g. per-profile status updates) can't interleave
     * load/persist pairs and silently drop each other's changes. Refuses to
     * overwrite a stored payload that no longer decodes, instead of wiping
     * every profile on corrupt JSON.
     */
    private suspend fun mutate(block: (List<ServerProfile>) -> List<ServerProfile>?) {
        writeMutex.withLock {
            context.profileDataStore.edit { prefs ->
                val raw = prefs[key]
                val current = decode(raw)
                if (raw != null && current.isEmpty() && raw.isNotBlank()) {
                    throw IllegalStateException("refusing to overwrite unreadable profiles")
                }
                val next = block(current) ?: return@edit
                prefs[key] = OpenCodeJson.encodeToString(
                    ListSerializer(ServerProfile.serializer()),
                    next,
                )
            }
        }
    }

    private fun generateId(): String = UUID.randomUUID().toString()

    suspend fun create(data: ServerProfile): ServerProfile {
        val profile = data.copy(id = data.id.ifBlank { generateId() })
        mutate { current ->
            val next = current.toMutableList()
            if (profile.isDefault) next.replaceAll { it.copy(isDefault = false) }
            next += profile
            next
        }
        return profile
    }

    suspend fun update(id: String, transform: (ServerProfile) -> ServerProfile): ServerProfile? {
        var result: ServerProfile? = null
        mutate { current ->
            val mutable = current.toMutableList()
            val index = mutable.indexOfFirst { it.id == id }
            if (index == -1) return@mutate null
            var next = transform(mutable[index]).copy(id = id)
            if (next.isDefault) {
                for (i in mutable.indices) {
                    if (i != index) mutable[i] = mutable[i].copy(isDefault = false)
                }
            }
            mutable[index] = next
            result = next
            mutable
        }
        return result
    }

    suspend fun remove(id: String): Boolean {
        var removed = false
        mutate { current ->
            if (current.none { it.id == id }) return@mutate null
            removed = true
            current.filterNot { it.id == id }
        }
        return removed
    }

    suspend fun duplicate(id: String): ServerProfile? {
        val source = load().firstOrNull { it.id == id } ?: return null
        return create(
            source.copy(
                id = "",
                name = "${source.name} (copy)",
                isDefault = false,
                lastStatus = ServerStatus.UNKNOWN,
                lastConnectedAt = null,
            ),
        )
    }

    suspend fun setDefault(id: String): Boolean =
        update(id) { it.copy(isDefault = true) } != null
}
