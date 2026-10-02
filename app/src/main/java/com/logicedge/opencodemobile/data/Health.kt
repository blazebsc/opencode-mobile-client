package com.logicedge.opencodemobile.data

object Health {
    const val DEFAULT_TIMEOUT_MS = 3_500L

    fun classifyStatus(statusCode: Int, hadAuth: Boolean): ServerStatus = when {
        statusCode in 200..399 -> ServerStatus.CONNECTED
        statusCode == 401 || statusCode == 403 ->
            if (hadAuth) ServerStatus.WRONG_CREDENTIALS else ServerStatus.AUTH_REQUIRED
        else -> ServerStatus.UNREACHABLE
    }
}
