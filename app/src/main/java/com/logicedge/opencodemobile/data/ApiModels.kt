package com.logicedge.opencodemobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

val OpenCodeJson = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
data class SessionTime(val created: Long = 0L, val updated: Long = 0L)

@Serializable
data class SessionInfo(
    val id: String,
    val title: String? = null,
    val projectID: String? = null,
    val agent: String? = null,
    val time: SessionTime = SessionTime(),
)

sealed interface ChatMessage {
    val id: String
    val created: Long
}

data class UserMessage(
    override val id: String,
    override val created: Long,
    val text: String,
) : ChatMessage

data class AssistantMessage(
    override val id: String,
    override val created: Long,
    val agent: String? = null,
    val model: String? = null,
    val textParts: List<String> = emptyList(),
    val thinkingParts: List<String> = emptyList(),
    val toolCalls: List<String> = emptyList(),
    val finish: String? = null,
    val error: String? = null,
) : ChatMessage

data class SystemMessage(
    override val id: String,
    override val created: Long,
    val text: String,
) : ChatMessage

object MessageParser {
    fun parseList(raw: String, version: ApiVersion = ApiVersion.V2): List<ChatMessage> {
        val root = runCatching { OpenCodeJson.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyList()
        val data = runCatching {
            if (version == ApiVersion.V1) {
                root.jsonArray
            } else {
                root.jsonObject["data"]?.jsonArray
            }
        }.getOrNull() ?: return emptyList()
        return data.mapNotNull {
            runCatching { parseOne(it.jsonObject, version) }.getOrNull()
        }
    }

    fun parseOne(obj: JsonObject, version: ApiVersion = ApiVersion.V2): ChatMessage? {
        // v1 wraps messages as {info, parts}; v2 is flat.
        if (version == ApiVersion.V1 && obj.containsKey("info")) {
            return parseV1(obj.jsonObject["info"]!!.jsonObject, obj["parts"]?.jsonArray)
        }
        return parseV2(obj)
    }

    private fun parseV1(info: JsonObject, parts: JsonArray?): ChatMessage? {
        val id = info["id"]?.jsonPrimitive?.content ?: return null
        val type = info["type"]?.jsonPrimitive?.content ?: return null
        val created = info["time"]?.jsonObject?.get("created")?.jsonPrimitive?.longOrNull ?: 0L
        val texts = mutableListOf<String>()
        val thinking = mutableListOf<String>()
        val tools = mutableListOf<String>()
        parts?.forEach { part ->
            val p = part.jsonObject
            when (p["type"]?.jsonPrimitive?.content) {
                "text" -> p["text"]?.jsonPrimitive?.content?.let { texts += it }
                "reasoning" -> p["text"]?.jsonPrimitive?.content?.let { thinking += it }
                "tool" -> tools += p["tool"]?.jsonPrimitive?.content
                    ?: p["name"]?.jsonPrimitive?.content ?: "tool"
            }
        }
        return when (type) {
            "user" -> UserMessage(
                id, created,
                info["text"]?.jsonPrimitive?.content ?: texts.firstOrNull().orEmpty(),
            )
            "assistant" -> AssistantMessage(
                id, created,
                agent = info["agent"]?.jsonPrimitive?.content,
                model = info["model"]?.jsonObject?.get("id")?.jsonPrimitive?.content,
                textParts = texts,
                thinkingParts = thinking,
                toolCalls = tools,
                finish = info["finish"]?.jsonPrimitive?.content,
                error = info["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content,
            )
            else -> SystemMessage(id, created, texts.firstOrNull() ?: type)
        }
    }

    private fun parseV2(obj: JsonObject): ChatMessage? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        val time = obj["time"]?.jsonObject
        val created = time?.get("created")?.jsonPrimitive?.longOrNull ?: 0L
        return when (type) {
            "user" -> UserMessage(
                id = id,
                created = created,
                text = obj["text"]?.jsonPrimitive?.content
                    ?: obj["payload"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                    ?: "",
            )
            "assistant" -> {
                val texts = mutableListOf<String>()
                val thinking = mutableListOf<String>()
                val tools = mutableListOf<String>()
                obj["content"]?.jsonArray?.forEach { part ->
                    val p = part.jsonObject
                    when (p["type"]?.jsonPrimitive?.content) {
                        "text" -> p["text"]?.jsonPrimitive?.content?.let { texts += it }
                        "reasoning" -> p["text"]?.jsonPrimitive?.content?.let { thinking += it }
                        "tool" -> {
                            val name = p["tool"]?.jsonPrimitive?.content
                                ?: p["name"]?.jsonPrimitive?.content
                                ?: "tool"
                            tools += name
                        }
                    }
                }
                AssistantMessage(
                    id = id,
                    created = created,
                    agent = obj["agent"]?.jsonPrimitive?.content,
                    model = obj["model"]?.jsonObject?.get("id")?.jsonPrimitive?.content,
                    textParts = texts,
                    thinkingParts = thinking,
                    toolCalls = tools,
                    finish = obj["finish"]?.jsonPrimitive?.content,
                    error = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content,
                )
            }
            "idle" -> SystemMessage(id, created, obj["outcome"]?.jsonPrimitive?.content ?: "idle")
            else -> {
                val fallback = obj["text"]?.jsonPrimitive?.content
                if (fallback != null) SystemMessage(id, created, fallback) else null
            }
        }
    }
}
