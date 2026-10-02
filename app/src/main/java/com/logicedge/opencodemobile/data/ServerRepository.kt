package com.logicedge.opencodemobile.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import java.time.Instant

/**
 * Pure planner for profile-list repair, kept free of Android types so the
 * rules are unit-testable:
 *  - removes duplicate "Local OpenCode" entries left by an old seeding bug
 *    (they never received the stable embedded id and multiplied on every load)
 *  - decides whether the embedded profile must be (re-)created
 *  - picks a default server when none is set (the landing screen only shows
 *    the default, so without this a freshly added server is invisible)
 */
object ProfilePlan {
    data class Plan(
        val removeIds: List<String>,
        val needEmbedded: Boolean,
        val promoteDefaultId: String?,
    )

    fun plan(raw: List<ServerProfile>): Plan {
        val removeIds = raw
            .filter {
                it.id != ServerRepository.EMBEDDED_SERVER_ID &&
                    it.name == "Local OpenCode" &&
                    it.baseUrl == ServerRepository.EMBEDDED_SERVER_URL
            }
            .map { it.id }
        val kept = raw.filterNot { removeIds.contains(it.id) }
        val needEmbedded = kept.none { it.id == ServerRepository.EMBEDDED_SERVER_ID }
        val promoteDefaultId = if (kept.any { it.isDefault }) {
            null
        } else {
            kept.firstOrNull { it.id != ServerRepository.EMBEDDED_SERVER_ID }?.id
        }
        return Plan(removeIds, needEmbedded, promoteDefaultId)
    }
}

class ServerRepository(
    private val profiles: ProfileStore,
    private val secrets: SecretStore,
) {
    suspend fun load(): List<ServerProfile> {
        val plan = ProfilePlan.plan(profiles.load())
        plan.removeIds.forEach { profiles.remove(it) }
        if (plan.needEmbedded) {
            profiles.create(
                ServerProfile(
                    id = EMBEDDED_SERVER_ID,
                    name = "Local OpenCode",
                    baseUrl = EMBEDDED_SERVER_URL,
                ),
            )
        }
        if (plan.promoteDefaultId != null) {
            profiles.update(plan.promoteDefaultId) { it.copy(isDefault = true) }
        }
        return profiles.load()
    }

    suspend fun apiFor(profile: ServerProfile): OpenCodeApi {
        val password = if (profile.authEnabled) secrets.get(profile.id) else null
        return OpenCodeApi(profile.baseUrl, profile.username, password, resolvedVersion(profile))
    }

    private fun resolvedVersion(profile: ServerProfile): ApiVersion =
        if (profile.apiVersion == ApiVersion.UNKNOWN) ApiVersion.V2 else profile.apiVersion

    suspend fun checkHealth(profile: ServerProfile, timeoutMs: Long): HealthResult {
        if (DemoMode.isDemoCredentials(profile.username, secrets.get(profile.id))) {
            return HealthResult(true, ServerStatus.CONNECTED, 200)
        }
        val password = if (profile.authEnabled) secrets.get(profile.id) else null
        return try {
            if (profile.apiVersion == ApiVersion.UNKNOWN) {
                val detection = ServerDetector.detect(profile.baseUrl, profile.username, password, timeoutMs)
                if (detection.version != ApiVersion.UNKNOWN) {
                    profiles.update(profile.id) {
                        it.copy(apiVersion = detection.version, serverVersion = detection.serverVersion)
                    }
                }
                HealthResult(
                    detection.status != ServerStatus.UNREACHABLE,
                    detection.status,
                    detection.statusCode,
                )
            } else {
                apiFor(profile).healthCheck(timeoutMs)
            }
        } catch (e: Exception) {
            HealthResult(false, ServerStatus.UNREACHABLE)
        }
    }

    suspend fun checkAll(timeoutMs: Long = Health.DEFAULT_TIMEOUT_MS): List<ServerProfile> =
        supervisorScope {
            val current = profiles.load()
            val results = current.map { profile ->
                async {
                    val result = checkHealth(profile, timeoutMs)
                    if (profile.lastStatus != result.status) {
                        profiles.update(profile.id) { it.copy(lastStatus = result.status) }
                        profile.copy(lastStatus = result.status)
                    } else {
                        profile
                    }
                }
            }.awaitAll()
            results
        }

    suspend fun markConnected(profile: ServerProfile) {
        profiles.update(profile.id) {
            it.copy(lastStatus = ServerStatus.CONNECTED, lastConnectedAt = Instant.now().toString())
        }
    }

    suspend fun deleteProfile(id: String) {
        profiles.remove(id)
        secrets.delete(id)
    }

    suspend fun createProfile(profile: ServerProfile): ServerProfile =
        profiles.create(profile.copy(id = ""))

    suspend fun updateProfile(id: String, transform: (ServerProfile) -> ServerProfile): ServerProfile? =
        profiles.update(id, transform)

    suspend fun duplicateProfile(id: String): ServerProfile? =
        profiles.duplicate(id)

    suspend fun setDefaultProfile(id: String): Boolean =
        profiles.setDefault(id)

    suspend fun getPassword(id: String): String? = secrets.get(id)

    suspend fun setPassword(id: String, secret: String) = secrets.set(id, secret)

    suspend fun deletePassword(id: String) = secrets.delete(id)

    companion object {
        const val EMBEDDED_SERVER_ID = "embedded-local-server"
        const val EMBEDDED_SERVER_URL = "http://127.0.0.1:4096"
    }
}
