package com.logicedge.opencodemobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePlanTest {

    private fun profile(
        id: String,
        name: String = "S",
        url: String = "http://10.0.0.1:4096",
        isDefault: Boolean = false,
    ) = ServerProfile(id = id, name = name, baseUrl = url, isDefault = isDefault)

    @Test
    fun freshInstallWantsEmbeddedOnlyAndNoDefault() {
        val plan = ProfilePlan.plan(emptyList())
        assertTrue(plan.removeIds.isEmpty())
        assertTrue(plan.needEmbedded)
        assertNull(plan.promoteDefaultId)
    }

    @Test
    fun promotesFirstRealServerWhenNoDefaultExists() {
        val raw = listOf(
            profile(ServerRepository.EMBEDDED_SERVER_ID, "Local OpenCode", "http://127.0.0.1:4096"),
            profile("real"),
        )
        val plan = ProfilePlan.plan(raw)
        assertFalse(plan.needEmbedded)
        assertEquals("real", plan.promoteDefaultId)
    }

    @Test
    fun keepsExistingDefault() {
        val raw = listOf(profile("real", isDefault = true))
        val plan = ProfilePlan.plan(raw)
        assertNull(plan.promoteDefaultId)
        assertTrue(plan.removeIds.isEmpty())
    }

    @Test
    fun removesLegacyDuplicateEmbeddedProfiles() {
        val raw = listOf(
            profile("u1", "Local OpenCode", "http://127.0.0.1:4096"),
            profile("u2", "Local OpenCode", "http://127.0.0.1:4096"),
            profile("real"),
        )
        val plan = ProfilePlan.plan(raw)
        assertEquals(listOf("u1", "u2"), plan.removeIds)
        assertTrue(plan.needEmbedded)
        assertEquals("real", plan.promoteDefaultId)
    }

    @Test
    fun neverPromotesEmbeddedProfileAlone() {
        val raw = listOf(
            profile(ServerRepository.EMBEDDED_SERVER_ID, "Local OpenCode", "http://127.0.0.1:4096"),
        )
        val plan = ProfilePlan.plan(raw)
        assertFalse(plan.needEmbedded)
        assertNull(plan.promoteDefaultId)
    }
}
