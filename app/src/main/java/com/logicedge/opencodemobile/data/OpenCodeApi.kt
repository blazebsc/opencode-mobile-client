package com.logicedge.opencodemobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class OpenCodeApi(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    val version: ApiVersion = ApiVersion.V2,
) {
    private val prefix: String get() = if (version == ApiVersion.V1) "" else "/api"

    companion object {
        const val DEFAULT_API_TIMEOUT_MS = 60_000L
        const val WAIT_CALL_TIMEOUT_MS = 125_000L
    }

    private fun headers(): Map<String, String> =
        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            mapOf("Authorization" to Auth.basicAuthHeader(username, password))
        } else {
            emptyMap()
        }

    private fun request(method: String, path: String, body: String? = null): Request {
        val builder = Request.Builder().url(UrlUtils.normalizeUrl(baseUrl) + path)
        headers().forEach { (k, v) -> builder.header(k, v) }
        val mediaType = "application/json".toMediaType()
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((body ?: "{}").toRequestBody(mediaType))
            "PATCH" -> builder.patch((body ?: "{}").toRequestBody(mediaType))
            "HEAD" -> builder.head()
            else -> error("unsupported method $method")
        }
        return builder.build()
    }

    private suspend fun execute(
        method: String,
        path: String,
        body: String? = null,
        timeoutMs: Long? = DEFAULT_API_TIMEOUT_MS,
    ): String = withContext(Dispatchers.IO) {
        val call = SharedHttp.client.newCall(request(method, path, body))
        val response = if (timeoutMs != null) {
            withTimeout(timeoutMs) { call.await() }
        } else {
            call.await()
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw ApiException(it.code, text.take(300))
            }
            text
        }
    }

    private fun dataElement(raw: String): JsonElement {
        val root = OpenCodeJson.parseToJsonElement(raw)
        return if (version == ApiVersion.V1) {
            root
        } else {
            root.jsonObject["data"]
                ?: throw ApiException(-1, "missing data envelope: ${raw.take(200)}")
        }
    }

    private fun dataObject(raw: String): JsonObject {
        val element = dataElement(raw)
        return element as? JsonObject
            ?: throw ApiException(-1, "expected object envelope: ${raw.take(200)}")
    }

    suspend fun healthCheck(timeoutMs: Long = Health.DEFAULT_TIMEOUT_MS): HealthResult {
        val hadAuth = !username.isNullOrEmpty() && !password.isNullOrEmpty()
        try {
            if (version == ApiVersion.V1) {
                val raw = execute("GET", "/global/health", timeoutMs = timeoutMs)
                return if (ServerDetector.parseHealth(raw) != null) {
                    HealthResult(true, ServerStatus.CONNECTED)
                } else {
                    HealthResult(false, ServerStatus.UNREACHABLE)
                }
            }
            val raw = execute("GET", "$prefix/info", timeoutMs = timeoutMs)
            return if (ServerDetector.parseInfoVersion(raw) != null) {
                HealthResult(true, ServerStatus.CONNECTED)
            } else {
                HealthResult(false, ServerStatus.UNREACHABLE)
            }
        } catch (e: ApiException) {
            // An HTTP answer is decisive: only transport failure falls through.
            val status = when (e.code) {
                401, 403 -> if (hadAuth) ServerStatus.WRONG_CREDENTIALS else ServerStatus.AUTH_REQUIRED
                else -> ServerStatus.UNREACHABLE
            }
            return HealthResult(status != ServerStatus.UNREACHABLE, status, e.code)
        } catch (e: TimeoutCancellationException) {
            return HealthResult(false, ServerStatus.UNREACHABLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return HealthResult(false, ServerStatus.UNREACHABLE)
        }
    }

    suspend fun listSessions(): List<SessionInfo> {
        val data = dataElement(execute("GET", "$prefix/session"))
        return OpenCodeJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(SessionInfo.serializer()),
            data,
        )
    }

    suspend fun createSession(title: String? = null): SessionInfo {
        val body = if (title != null) {
            buildJsonObject { put("title", title) }.toString()
        } else {
            null
        }
        return OpenCodeJson.decodeFromJsonElement(
            SessionInfo.serializer(),
            dataObject(execute("POST", "$prefix/session", body)),
        )
    }

    suspend fun deleteSession(sessionId: String) {
        execute("DELETE", "$prefix/session/$sessionId")
    }

    suspend fun renameSession(sessionId: String, title: String) {
        val body = buildJsonObject { put("title", title) }.toString()
        execute("PATCH", "$prefix/session/$sessionId", body)
    }

    suspend fun listModels(): List<ChatModel> =
        runCatching {
            CatalogParser.parseModels(execute("GET", "$prefix/model"), version)
        }.getOrDefault(emptyList())

    suspend fun listAgents(): List<ChatAgent> =
        runCatching {
            CatalogParser.parseAgents(execute("GET", "$prefix/agent"), version)
        }.getOrDefault(emptyList())

    suspend fun switchModel(sessionId: String, providerID: String, id: String) {
        val body = buildJsonObject {
            putJsonObject("model") {
                put("providerID", providerID)
                put("id", id)
            }
        }.toString()
        execute("POST", "$prefix/session/$sessionId/model", body)
    }

    suspend fun switchAgent(sessionId: String, name: String) {
        val body = buildJsonObject { put("agent", name) }.toString()
        execute("POST", "$prefix/session/$sessionId/agent", body)
    }

    suspend fun getMessages(sessionId: String): List<ChatMessage> =
        MessageParser.parseList(execute("GET", "$prefix/session/$sessionId/message"), version)

    suspend fun sendPrompt(sessionId: String, text: String): String {
        return if (version == ApiVersion.V1) {
            // v1 waits for the full agent response: no call timeout, the
            // caller cancels via stop() and the settle loop bounds the wait.
            val body = buildJsonObject {
                put(
                    "parts",
                    kotlinx.serialization.json.buildJsonArray {
                        addJsonObject { put("type", "text"); put("text", text) }
                    },
                )
            }.toString()
            execute("POST", "$prefix/session/$sessionId/message", body, timeoutMs = null)
        } else {
            val body = buildJsonObject { put("text", text) }.toString()
            execute("POST", "$prefix/session/$sessionId/prompt", body, timeoutMs = null)
        }
    }

    suspend fun waitForSession(sessionId: String) {
        if (version == ApiVersion.V1) return
        runCatching {
            execute(
                "POST",
                "$prefix/experimental/session/$sessionId/wait",
                "{}",
                timeoutMs = WAIT_CALL_TIMEOUT_MS,
            )
        }
    }

    suspend fun interruptSession(sessionId: String) {
        if (version == ApiVersion.V1) {
            execute("POST", "$prefix/session/$sessionId/abort", "{}")
        } else {
            execute("POST", "$prefix/session/$sessionId/interrupt", "{}")
        }
    }

    suspend fun listCommands(): List<SlashCommand> =
        runCatching {
            CommandsParser.parseList(execute("GET", "$prefix/command"), version)
        }.getOrDefault(emptyList())

    suspend fun runCommand(sessionId: String, name: String, args: String): String {
        val body = buildJsonObject {
            put("name", name)
            put("text", args)
        }.toString()
        return execute("POST", "$prefix/session/$sessionId/command", body, timeoutMs = null)
    }

    suspend fun listPermissions(sessionId: String): List<PermissionRequest> {        if (version == ApiVersion.V1) {
            // Undocumented on v1 (requests arrive over the event stream there);
            // best-effort probe, empty on any failure.
            return runCatching {
                PermissionsParser.parseList(execute("GET", "$prefix/session/$sessionId/permissions"))
            }.getOrDefault(emptyList())
        }
        return PermissionsParser.parseList(execute("GET", "$prefix/session/$sessionId/permission"))
    }

    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
        message: String? = null,
    ) {
        if (version == ApiVersion.V1) {
            // v1 docs only specify {response, remember?}; the once/always/reject
            // vocabulary is inherited from the shared permission model.
            val body = buildJsonObject {
                put("response", decision.serial)
                put("remember", decision == PermissionDecision.ALWAYS)
                if (message != null) put("message", message)
            }.toString()
            execute("POST", "$prefix/session/$sessionId/permissions/$requestId", body)
        } else {
            val body = buildJsonObject {
                put("decision", decision.serial)
                if (message != null) put("message", message)
            }.toString()
            execute("POST", "$prefix/session/$sessionId/permission/$requestId/reply", body)
        }
    }

    fun subscribeEvents(listener: EventSourceListener): EventSource {
        val streamPath = if (version == ApiVersion.V1) "/global/event" else "$prefix/event"
        val builder = Request.Builder().url(UrlUtils.normalizeUrl(baseUrl) + streamPath)
            .header("Accept", "text/event-stream")
        headers().forEach { (k, v) -> builder.header(k, v) }
        return EventSources.createFactory(SharedHttp.client).newEventSource(builder.build(), listener)
    }
}

object Pairing {
    private val linkPattern =
        Regex("""(https?://(?:\[[^\]]+\]|[^/\s:]+)(?::\d+)?)/auth/connect/([A-Za-z0-9_-]+)""")

    sealed interface RedeemResult {
        data class Token(val token: String) : RedeemResult
        data object Unreachable : RedeemResult
        data object Rejected : RedeemResult
    }

    fun parseLink(text: String): Pair<String, String>? {
        val match = linkPattern.find(text.trim()) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    suspend fun redeem(baseUrl: String, code: String, timeoutMs: Long = 15_000L): RedeemResult =
        withContext(Dispatchers.IO) {
            try {
            val request = Request.Builder()
                .url(UrlUtils.normalizeUrl(baseUrl) + "/auth/connect/" + encodePathSegment(code))
                .header("Accept", "application/json")
                .get()
                .build()
            val call = SharedHttp.client.newCall(request)
            val response = withTimeout(timeoutMs) { call.await() }
            response.use {
                if (!it.isSuccessful) return@use RedeemResult.Rejected
                val body = it.body?.string().orEmpty()
                val token = runCatching {
                    val root = OpenCodeJson.parseToJsonElement(body).jsonObject
                    root["token"]?.jsonPrimitive?.content
                        ?: root["data"]?.jsonObject?.get("token")?.jsonPrimitive?.content
                }.getOrNull()
                if (token != null) RedeemResult.Token(token) else RedeemResult.Rejected
            }
        } catch (e: TimeoutCancellationException) {
            RedeemResult.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RedeemResult.Unreachable
        }
    }

    fun encodePathSegment(segment: String): String =
        segment.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~') {
                c.toString()
            } else {
                c.toString().toByteArray(Charsets.UTF_8)
                    .joinToString("") { "%${it.toUByte().toString(16).uppercase().padStart(2, '0')}" }
            }
        }.joinToString("")
}

class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")

data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val action: String,
    val resources: List<String> = emptyList(),
    val message: String? = null,
)

enum class PermissionDecision(val serial: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}
