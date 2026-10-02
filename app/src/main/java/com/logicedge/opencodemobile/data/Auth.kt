package com.logicedge.opencodemobile.data

object Auth {
    fun basicAuthHeader(username: String, password: String): String {
        val credentials = "$username:$password"
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(credentials.toByteArray(Charsets.UTF_8))
        return "Basic $encoded"
    }
}
