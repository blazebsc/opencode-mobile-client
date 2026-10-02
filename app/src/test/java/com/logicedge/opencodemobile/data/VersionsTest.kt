package com.logicedge.opencodemobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionDetectionTest {

    @Test
    fun parsesV2Info() {
        assertEquals(
            "2.0.18",
            ServerDetector.parseInfoVersion("""{"version":"2.0.18","pid":1,"urls":[]}"""),
        )
        assertNull(ServerDetector.parseInfoVersion("<!doctype html><html></html>"))
        assertNull(ServerDetector.parseInfoVersion("garbage"))
    }

    @Test
    fun parsesV1Health() {
        assertEquals(
            "1.2.3",
            ServerDetector.parseHealth("""{"healthy":true,"version":"1.2.3"}"""),
        )
        assertNull(ServerDetector.parseHealth("<!doctype html><html></html>"))
        assertNull(ServerDetector.parseHealth("""{"healthy":false}"""))
        assertNull(ServerDetector.parseHealth("garbage"))
    }
}

class V1MessageParserTest {

    @Test
    fun parsesV1InfoPartsShape() {
        val raw = """[{"info":{"id":"msg_1","type":"user","time":{"created":10},"text":"hi"},"parts":[]},
          {"info":{"id":"msg_2","type":"assistant","time":{"created":20},"agent":"build",
           "model":{"id":"m","providerID":"p"},"finish":"stop"},
           "parts":[{"type":"text","text":"hello"},{"type":"tool","tool":"read"}]}]"""
        val messages = MessageParser.parseList(raw, ApiVersion.V1)
        assertEquals(2, messages.size)
        assertEquals("hi", (messages[0] as UserMessage).text)
        val assistant = messages[1] as AssistantMessage
        assertEquals(listOf("hello"), assistant.textParts)
        assertEquals(listOf("read"), assistant.toolCalls)
        assertEquals("build", assistant.agent)
    }

    @Test
    fun v1SystemFallback() {
        val raw = """[{"info":{"id":"msg_9","type":"mystery","time":{"created":1}},"parts":[]}]"""
        val messages = MessageParser.parseList(raw, ApiVersion.V1)
        assertEquals(1, messages.size)
        assertTrue(messages[0] is SystemMessage)
    }
}

class PairingLinkTest {
    @Test
    fun parsesPairingLinks() {
        val parsed = Pairing.parseLink("http://192.168.1.10:4096/auth/connect/ABC123")
        assertEquals("http://192.168.1.10:4096", parsed?.first)
        assertEquals("ABC123", parsed?.second)
        assertEquals(
            "https://example.com",
            Pairing.parseLink("  https://example.com/auth/connect/x-y_Z  ")?.first,
        )
        assertNull(Pairing.parseLink("http://192.168.1.10:4096/"))
        assertNull(Pairing.parseLink("not a link"))
    }

    @Test
    fun parsesIpv6PairingLinks() {
        val parsed = Pairing.parseLink("http://[::1]:4096/auth/connect/ABC")
        assertEquals("http://[::1]:4096", parsed?.first)
        assertEquals("ABC", parsed?.second)
    }

    @Test
    fun encodesPathSegments() {
        assertEquals("ABC-123_x", Pairing.encodePathSegment("ABC-123_x"))
        assertEquals("a%2Fb", Pairing.encodePathSegment("a/b"))
    }
}

class CommandsParserTest {
    @Test
    fun parsesV2Envelope() {
        val raw = """{"data":[
          {"name":"init","description":"Analyze app"},
          {"name":"undo"}
        ]}"""
        val list = CommandsParser.parseList(raw, ApiVersion.V2)
        assertEquals(2, list.size)
        assertEquals("init", list[0].name)
        assertEquals("Analyze app", list[0].description)
        assertNull(list[1].description)
    }

    @Test
    fun parsesV1RawArray() {
        val raw = """[{"name":"share","description":"Share session"}]"""
        val list = CommandsParser.parseList(raw, ApiVersion.V1)
        assertEquals(1, list.size)
        assertEquals("share", list[0].name)
    }

    @Test
    fun skipsNamelessAndGarbage() {
        assertTrue(CommandsParser.parseList("""{"data":[{"description":"x"}]}""").isEmpty())
        assertTrue(CommandsParser.parseList("nope").isEmpty())
    }
}

class CatalogParserTest {

    @Test
    fun parsesModelsWithDisplayNames() {
        val raw = """{"data":[
          {"id":"qwen3","providerID":"ollama","name":"Qwen 3"},
          {"id":"m","providerID":"p"}
        ]}"""
        val list = CatalogParser.parseModels(raw, ApiVersion.V2)
        assertEquals(2, list.size)
        assertEquals("Qwen 3", list[0].displayName)
        assertEquals("p/m", list[1].displayName)
    }

    @Test
    fun parsesAgentsDefensively() {
        val raw = """{"data":[{"name":"build","description":"Build stuff"},{"name":"plan"}]}"""
        val list = CatalogParser.parseAgents(raw, ApiVersion.V2)
        assertEquals(2, list.size)
        assertEquals("Build stuff", list[0].description)
        assertNull(list[1].description)
        assertTrue(CatalogParser.parseAgents("nope").isEmpty())
    }
}
