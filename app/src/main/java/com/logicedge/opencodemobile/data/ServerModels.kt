package com.logicedge.opencodemobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authEnabled: Boolean = false,
    val username: String = "",
    val isDefault: Boolean = false,
    val allowInsecureHttp: Boolean = true,
    val lastStatus: ServerStatus = ServerStatus.UNKNOWN,
    val lastConnectedAt: String? = null,
    val apiVersion: ApiVersion = ApiVersion.UNKNOWN,
    val serverVersion: String? = null,
)

@Serializable
enum class ServerStatus(val serial: String) {
    @SerialName("unknown") UNKNOWN("unknown"),
    @SerialName("checking") CHECKING("checking"),
    @SerialName("connected") CONNECTED("connected"),
    @SerialName("auth_required") AUTH_REQUIRED("auth_required"),
    @SerialName("wrong_credentials") WRONG_CREDENTIALS("wrong_credentials"),
    @SerialName("unreachable") UNREACHABLE("unreachable"),
    @SerialName("frame_blocked") FRAME_BLOCKED("frame_blocked"),
}

enum class ConnectionState {
    IDLE,
    CHECKING,
    CONNECTED,
    AUTH_REQUIRED,
    WRONG_CREDENTIALS,
    UNREACHABLE,
    RECONNECTING,
    DISCONNECTED,
}

data class HealthResult(
    val reachable: Boolean,
    val status: ServerStatus,
    val statusCode: Int? = null,
)
