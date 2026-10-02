package com.logicedge.opencodemobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun normalizeAddsScheme() {
        assertEquals("http://192.168.1.10:4096", UrlUtils.normalizeUrl("192.168.1.10:4096"))
    }

    @Test
    fun normalizeKeepsHttpsAndTrimsSlashes() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl("  https://example.com///  "))
    }

    @Test
    fun normalizeHandlesUppercaseScheme() {
        assertEquals("HTTPS://example.com", UrlUtils.normalizeUrl("HTTPS://example.com"))
    }

    @Test
    fun validateRejectsBlank() {
        val result = UrlUtils.validateUrl("   ")
        assertFalse(result.valid)
        assertEquals("URL is required", result.error)
    }

    @Test
    fun validateRejectsMissingScheme() {
        val result = UrlUtils.validateUrl("192.168.1.10:4096")
        assertFalse(result.valid)
    }

    @Test
    fun validateRejectsPlaceholder() {
        val result = UrlUtils.validateUrl("http://192.168.1.xxx:4096")
        assertFalse(result.valid)
        assertTrue(result.error!!.contains("placeholder"))
    }

    @Test
    fun validateRejectsEmbeddedCredentials() {
        val result = UrlUtils.validateUrl("http://user:pass@192.168.1.10:4096")
        assertFalse(result.valid)
    }

    @Test
    fun validateAcceptsLanHttp() {
        val result = UrlUtils.validateUrl("http://192.168.1.10:4096")
        assertTrue(result.valid)
        assertNull(result.error)
    }

    @Test
    fun validateWarnsOnPublicHttp() {
        val result = UrlUtils.validateUrl("http://example.com:4096")
        assertTrue(result.valid)
        assertTrue(result.error!!.contains("not secure"))
    }

    @Test
    fun validateAcceptsHttps() {
        val result = UrlUtils.validateUrl("https://example.com:4096")
        assertTrue(result.valid)
        assertNull(result.error)
    }

    @Test
    fun localHostsCoverLoopbackPrivateAndTailscale() {
        assertTrue(UrlUtils.isLocalOrPrivateHost("localhost"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("server.local"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("127.0.0.1"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("192.168.0.5"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("10.0.0.9"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("172.16.4.2"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("172.31.255.255"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("100.64.0.1"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("100.127.255.255"))
        assertFalse(UrlUtils.isLocalOrPrivateHost("example.com"))
        assertFalse(UrlUtils.isLocalOrPrivateHost("172.15.0.1"))
        assertFalse(UrlUtils.isLocalOrPrivateHost("100.128.0.1"))
    }

    @Test
    fun rejectsInvalidOctets() {
        assertFalse(UrlUtils.isLocalOrPrivateHost("192.168.999.999"))
        assertFalse(UrlUtils.isLocalOrPrivateHost("10.0.0.256"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("10.0.0.255"))
    }

    @Test
    fun coversIpv6Local() {
        assertTrue(UrlUtils.isLocalOrPrivateHost("::1"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("fe80::1"))
        assertTrue(UrlUtils.isLocalOrPrivateHost("fd00::1"))
        assertFalse(UrlUtils.isLocalOrPrivateHost("2001:db8::1"))
    }

    @Test
    fun detectsPublicHttp() {
        assertTrue(UrlUtils.isPublicHttp("http://example.com:4096"))
        assertFalse(UrlUtils.isPublicHttp("http://192.168.1.10:4096"))
        assertFalse(UrlUtils.isPublicHttp("https://example.com:4096"))
        assertFalse(UrlUtils.isPublicHttp("not a url"))
    }

    @Test
    fun sanitizeStripsCredentials() {
        val clean = UrlUtils.sanitizeUrlForDisplay("http://user:pass@host:4096/")
        assertFalse(clean.contains("pass"))
        assertTrue(clean.contains("host:4096"))
    }
}
