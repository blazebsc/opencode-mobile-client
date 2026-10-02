package com.logicedge.opencodemobile.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PermissionsParser {
    fun parseList(raw: String, version: ApiVersion = ApiVersion.V2): List<PermissionRequest> {
        val root = runCatching { OpenCodeJson.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyList()
        // v1 returns the raw array (like its message/model/agent endpoints);
        // v2 wraps it in a data envelope.
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
                PermissionRequest(
                    id = o.getValue("id").jsonPrimitive.content,
                    sessionID = o.getValue("sessionID").jsonPrimitive.content,
                    action = o.getValue("action").jsonPrimitive.content,
                    resources = o["resources"]?.jsonArray
                        ?.map { it.jsonPrimitive.content } ?: emptyList(),
                    message = o["message"]?.jsonPrimitive?.content,
                )
            }.getOrNull()
        }
    }
}
