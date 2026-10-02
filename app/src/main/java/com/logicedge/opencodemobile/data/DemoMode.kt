package com.logicedge.opencodemobile.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

object DemoMode {
    const val USERNAME = "demo_user_1234"
    const val PASSWORD = "demo_password_12345678"
    const val IP = "192.168.1.123"

    fun isDemoCredentials(username: String?, password: String?): Boolean =
        username == USERNAME && password == PASSWORD

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("connect|server", RegexOption.IGNORE_CASE) to
            "To connect, add your server's LAN address (for example http://192.168.1.50:4096) and make sure 'opencode serve --hostname 0.0.0.0 --port 4096' is running on your computer.",
        Regex("bug|fix|issue|error", RegexOption.IGNORE_CASE) to
            "If the app can't reach your server, verify the server is listening on 0.0.0.0 (not 127.0.0.1), the port is open, and both devices share the same network or VPN.",
        Regex("ui|design|mock", RegexOption.IGNORE_CASE) to
            "This demo conversation runs fully offline. Connect a real server to chat with your agents.",
        Regex("test|build|verify", RegexOption.IGNORE_CASE) to
            "Before release, run store verification, the type check, unit tests, and a production build.",
        Regex("demo", RegexOption.IGNORE_CASE) to
            "Demo mode is active: no network calls are made. Use the demo credentials on any server entry to explore the UI offline.",
    )
    private const val DEFAULT_REPLY =
        "This is a scripted demo reply. Connect to a live OpenCode server to talk to a real agent."

    fun replyTo(input: String): String =
        rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(input) }?.second ?: DEFAULT_REPLY
}

object DemoSession {
    fun seedMessages(profileName: String): List<ChatMessage> = listOf(
        SystemMessage("demo-sys-1", 0L, "Demo session for $profileName. No network calls are made."),
        UserMessage("demo-u-1", 1L, "How do I connect to my server?"),
        AssistantMessage(
            "demo-a-1", 2L,
            textParts = listOf(DemoMode.replyTo("How do I connect to my server?")),
        ),
    )
}
