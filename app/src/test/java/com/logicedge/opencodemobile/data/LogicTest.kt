package com.logicedge.opencodemobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {

    @Test
    fun basicHeaderMatchesRfc() {
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", Auth.basicAuthHeader("opencode", "secret"))
    }

    @Test
    fun basicHeaderHandlesUtf8() {
        val header = Auth.basicAuthHeader("user", "päss")
        assertTrue(header.startsWith("Basic "))
        val decoded = String(
            java.util.Base64.getDecoder().decode(header.removePrefix("Basic ")),
            Charsets.UTF_8,
        )
        assertEquals("user:päss", decoded)
    }

    @Test
    fun classifyStatusOk() {
        assertEquals(ServerStatus.CONNECTED, Health.classifyStatus(200, false))
        assertEquals(ServerStatus.CONNECTED, Health.classifyStatus(302, true))
    }

    @Test
    fun classifyStatusAuth() {
        assertEquals(ServerStatus.AUTH_REQUIRED, Health.classifyStatus(401, false))
        assertEquals(ServerStatus.WRONG_CREDENTIALS, Health.classifyStatus(401, true))
        assertEquals(ServerStatus.AUTH_REQUIRED, Health.classifyStatus(403, false))
        assertEquals(ServerStatus.WRONG_CREDENTIALS, Health.classifyStatus(403, true))
    }

    @Test
    fun classifyStatusUnreachable() {
        assertEquals(ServerStatus.UNREACHABLE, Health.classifyStatus(500, false))
        assertEquals(ServerStatus.UNREACHABLE, Health.classifyStatus(404, true))
    }
}

class DemoModeTest {

    @Test
    fun exactCredentialsMatch() {
        assertTrue(DemoMode.isDemoCredentials("demo_user_1234", "demo_password_12345678"))
        assertFalse(DemoMode.isDemoCredentials("demo_user_1234", "wrong"))
        assertFalse(DemoMode.isDemoCredentials(null, null))
    }

    @Test
    fun keywordReplies() {
        assertTrue(DemoMode.replyTo("how do I connect?").contains("LAN", ignoreCase = true))
        assertTrue(DemoMode.replyTo("there is a bug").contains("0.0.0.0"))
        assertEquals(
            "This is a scripted demo reply. Connect to a live OpenCode server to talk to a real agent.",
            DemoMode.replyTo("hello there"),
        )
    }
}

class MessageParserTest {

    @Test
    fun parsesUserAndAssistant() {
        val raw = """{"data":[
          {"id":"msg_1","time":{"created":10},"type":"user","text":"hi"},
          {"id":"msg_2","time":{"created":20},"type":"assistant","agent":"build",
           "model":{"id":"m","providerID":"p"},
           "content":[{"type":"reasoning","text":"let me think about this","state":"done"},
                      {"type":"text","text":"hello"},{"type":"tool","tool":"read"}],
           "finish":"stop"}
        ]}"""
        val messages = MessageParser.parseList(raw)
        assertEquals(2, messages.size)
        val user = messages[0] as UserMessage
        assertEquals("hi", user.text)
        val assistant = messages[1] as AssistantMessage
        assertEquals(listOf("hello"), assistant.textParts)
        assertEquals(listOf("let me think about this"), assistant.thinkingParts)
        assertEquals(listOf("read"), assistant.toolCalls)
        assertEquals("stop", assistant.finish)
    }

    @Test
    fun parsesEnvelopePromptUserShape() {
        val raw = """{"data":[
          {"id":"msg_3","sessionID":"ses_1","time":{"created":30},"type":"user",
           "payload":{"text":"PONG"},"delivery":"steer"}
        ]}"""
        val messages = MessageParser.parseList(raw)
        assertEquals(1, messages.size)
        assertEquals("PONG", (messages[0] as UserMessage).text)
    }

    @Test
    fun skipsUnknownWithoutText() {
        val raw = """{"data":[{"id":"msg_9","time":{"created":1},"type":"weird"}]}"""
        assertTrue(MessageParser.parseList(raw).isEmpty())
    }
}
