package com.logicedge.opencodemobile.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SlashCommand(
    val name: String,
    val description: String? = null,
)

object CommandsParser {
    fun parseList(raw: String, version: ApiVersion = ApiVersion.V2): List<SlashCommand> {
        val root = runCatching { OpenCodeJson.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyList()
        val data = runCatching {
            if (version == ApiVersion.V1) {
                root.jsonArray
            } else {
                root.jsonObject["data"]?.jsonArray
            }
        }.getOrNull() ?: return emptyList()
        return data.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                SlashCommand(
                    name = o.getValue("name").jsonPrimitive.content,
                    description = o["description"]?.jsonPrimitive?.content,
                )
            }.getOrNull()
        }
    }
}
