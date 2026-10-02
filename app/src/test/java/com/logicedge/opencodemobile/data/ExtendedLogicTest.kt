package com.logicedge.opencodemobile.data

import com.logicedge.opencodemobile.notify.NotifyDecision
import com.logicedge.opencodemobile.notify.NotifyPrefs
import com.logicedge.opencodemobile.notify.decideNotify
import com.logicedge.opencodemobile.ui.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {

    @Test
    fun parsesPermissionList() {
        val raw = """{"data":[{
          "id":"per_1","sessionID":"ses_1","action":"read",
          "resources":["/tmp/a.txt"],"message":"Allow read?"
        }]}"""
        val list = PermissionsParser.parseList(raw)
        assertEquals(1, list.size)
        assertEquals("per_1", list[0].id)
        assertEquals("read", list[0].action)
        assertEquals(listOf("/tmp/a.txt"), list[0].resources)
        assertEquals("Allow read?", list[0].message)
    }

    @Test
    fun emptyOnGarbage() {
        assertTrue(PermissionsParser.parseList("not json").isEmpty())
        assertTrue(PermissionsParser.parseList("""{"data":[]}""").isEmpty())
    }

    @Test
    fun parsesV1RawArray() {
        val raw = """[{
          "id":"per_1","sessionID":"ses_1","action":"read",
          "resources":["/tmp/a.txt"],"message":"Allow read?"
        }]"""
        val list = PermissionsParser.parseList(raw, ApiVersion.V1)
        assertEquals(1, list.size)
        assertEquals("per_1", list[0].id)
        assertEquals("read", list[0].action)
    }

    @Test
    fun decisionsMatchSpecSerials() {
        assertEquals("once", PermissionDecision.ONCE.serial)
        assertEquals("always", PermissionDecision.ALWAYS.serial)
        assertEquals("reject", PermissionDecision.REJECT.serial)
    }
}

class NotifyDecisionTest {

    @Test
    fun emitIgnoreAsk() {
        assertEquals(
            NotifyDecision.EMIT,
            decideNotify(NotifyPrefs(enabled = true)),
        )
        assertEquals(
            NotifyDecision.IGNORE,
            decideNotify(NotifyPrefs(settingsConfigured = true)),
        )
        assertEquals(
            NotifyDecision.IGNORE,
            decideNotify(NotifyPrefs(customPromptAnswered = true)),
        )
        assertEquals(
            NotifyDecision.ASK,
            decideNotify(NotifyPrefs()),
        )
    }
}

class SseEventSessionTest {

    @Test
    fun extractsNestedSessionId() {
        val data = """{"type":"session.step.started","data":{"sessionID":"ses_abc"}}"""
        assertEquals("ses_abc", ChatViewModel.extractEventSession(data))
    }

    @Test
    fun extractsTopLevelSessionId() {
        val data = """{"type":"server.connected","sessionID":"ses_x"}"""
        assertEquals("ses_x", ChatViewModel.extractEventSession(data))
    }

    @Test
    fun nullWithoutSession() {
        assertNull(ChatViewModel.extractEventSession("""{"type":"server.connected","data":{}}"""))
        assertNull(ChatViewModel.extractEventSession("garbage"))
    }
}
