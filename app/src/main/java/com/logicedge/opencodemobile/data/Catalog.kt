package com.logicedge.opencodemobile.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ChatModel(
    val id: String,
    val providerID: String,
    val displayName: String,
)

data class ChatAgent(
    val name: String,
    val description: String? = null,
)

object CatalogParser {
    fun parseModels(raw: String, version: ApiVersion = ApiVersion.V2): List<ChatModel> {
        val root = runCatching { OpenCodeJson.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyList()
        val data = runCatching {
            if (version == ApiVersion.V1) root.jsonArray else root.jsonObject["data"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return data.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val id = o.getValue("id").jsonPrimitive.content
                val provider = o.getValue("providerID").jsonPrimitive.content
                val name = o["name"]?.jsonPrimitive?.content
                ChatModel(id, provider, name ?: "$provider/$id")
            }.getOrNull()
        }
    }

    fun parseAgents(raw: String, version: ApiVersion = ApiVersion.V2): List<ChatAgent> {
        val root = runCatching { OpenCodeJson.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyList()
        val data = runCatching {
            if (version == ApiVersion.V1) root.jsonArray else root.jsonObject["data"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return data.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                ChatAgent(
                    name = o.getValue("name").jsonPrimitive.content,
                    description = o["description"]?.jsonPrimitive?.content,
                )
            }.getOrNull()
        }
    }
}
