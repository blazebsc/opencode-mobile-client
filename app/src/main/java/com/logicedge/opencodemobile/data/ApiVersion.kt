package com.logicedge.opencodemobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

@Serializable
enum class ApiVersion(val serial: String) {
    @SerialName("unknown") UNKNOWN("unknown"),
    @SerialName("v1") V1("v1"),
    @SerialName("v2") V2("v2"),
}

data class Detection(
    val version: ApiVersion,
    val status: ServerStatus,
    val statusCode: Int?,
    val serverVersion: String?,
)

object ServerDetector {

    suspend fun detect(
        baseUrl: String,
        username: String?,
        password: String?,
        timeoutMs: Long = Health.DEFAULT_TIMEOUT_MS,
    ): Detection = withContext(Dispatchers.IO) {
        val normalized = UrlUtils.normalizeUrl(baseUrl)
        val hadAuth = !username.isNullOrEmpty() && !password.isNullOrEmpty()

        fun authed(url: String): Request {
            val builder = Request.Builder().url(url).get()
            if (hadAuth) builder.header("Authorization", Auth.basicAuthHeader(username!!, password!!))
            return builder.build()
        }

        suspend fun probe(url: String): Pair<Int, String>? {
            return try {
                withTimeout(timeoutMs) {
                    SharedHttp.client.newCall(authed(url)).await().use { response ->
                        response.code to (response.body?.string().orEmpty())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        // v2: GET /api/info -> {"version": "2.x", ...}
        // (A v1 server answers 200 with the SPA HTML here, which fails JSON parsing.)
        probe("$normalized/api/info")?.let { (code, body) ->
            if (code == 401 || code == 403) {
                return@withContext Detection(
                    ApiVersion.V2,
                    if (hadAuth) ServerStatus.WRONG_CREDENTIALS else ServerStatus.AUTH_REQUIRED,
                    code, null,
                )
            }
            if (code in 200..399) {
                val version = parseInfoVersion(body)
                if (version != null) {
                    return@withContext Detection(ApiVersion.V2, ServerStatus.CONNECTED, code, version)
                }
            }
        }

        // v1: GET /global/health -> {"healthy": true, "version": "..."}
        // (A v2 server answers 200 with the SPA HTML here, which fails JSON parsing.)
        probe("$normalized/global/health")?.let { (code, body) ->
            if (code == 401 || code == 403) {
                return@withContext Detection(
                    ApiVersion.V1,
                    if (hadAuth) ServerStatus.WRONG_CREDENTIALS else ServerStatus.AUTH_REQUIRED,
                    code, null,
                )
            }
            if (code in 200..399) {
                val parsed = parseHealth(body)
                if (parsed != null) {
                    return@withContext Detection(ApiVersion.V1, ServerStatus.CONNECTED, code, parsed)
                }
            }
        }

        return@withContext Detection(ApiVersion.UNKNOWN, ServerStatus.UNREACHABLE, null, null)
    }

    fun parseInfoVersion(raw: String): String? = runCatching {
        OpenCodeJson.parseToJsonElement(raw).jsonObject["version"]?.jsonPrimitive?.content
    }.getOrNull()

    fun parseHealth(raw: String): String? = runCatching {
        val obj = OpenCodeJson.parseToJsonElement(raw).jsonObject
        val healthy = obj["healthy"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: return null
        if (!healthy) return null
        obj["version"]?.jsonPrimitive?.content
    }.getOrNull()
}
