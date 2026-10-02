package com.logicedge.opencodemobile.data

import java.net.URI

data class ValidationResult(val valid: Boolean, val error: String? = null)

object UrlUtils {

    fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            normalized = "http://$normalized"
        }
        return normalized.trimEnd('/')
    }

    fun validateUrl(url: String): ValidationResult {
        if (url.isBlank()) return ValidationResult(false, "URL is required")
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return ValidationResult(false, "URL must start with http:// or https://")
        }
        return try {
            val parsed = URI(trimmed)
            val host = parsed.host
                ?: return ValidationResult(false, "URL must include a hostname")
            if (host.isEmpty()) return ValidationResult(false, "URL must include a hostname")
            if (host.contains("xxx", ignoreCase = true)) {
                return ValidationResult(
                    false,
                    "Replace the placeholder URL with your server's actual address",
                )
            }
            if (!parsed.userInfo.isNullOrEmpty()) {
                return ValidationResult(
                    false,
                    "Embedded credentials in URL are not allowed. Use the separate auth fields instead.",
                )
            }
            if (parsed.scheme.equals("http", ignoreCase = true) && !isLocalOrPrivateHost(host)) {
                return ValidationResult(
                    true,
                    "Using HTTP over the internet is not secure. Use HTTPS or a VPN for remote connections.",
                )
            }
            ValidationResult(true)
        } catch (e: Exception) {
            ValidationResult(false, "Invalid URL format")
        }
    }

    private const val OCTET = """(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"""

    fun isLocalOrPrivateHost(hostname: String): Boolean {
        val host = hostname.lowercase()
        return host == "localhost" ||
            host == "::1" ||
            host.endsWith(".local") ||
            host.startsWith("fe80:") ||
            host.matches(Regex("""f[c-d][0-9a-f:]+""")) ||
            host.matches(Regex("""127\.$OCTET\.$OCTET\.$OCTET""")) ||
            host.matches(Regex("""192\.168\.$OCTET\.$OCTET""")) ||
            host.matches(Regex("""10\.$OCTET\.$OCTET\.$OCTET""")) ||
            host.matches(Regex("""172\.(1[6-9]|2\d|3[01])\.$OCTET\.$OCTET""")) ||
            host.matches(Regex("""100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.$OCTET\.$OCTET"""))
    }

    fun isPublicHttp(url: String): Boolean {
        return try {
            val parsed = URI(url.trim())
            parsed.scheme.equals("http", ignoreCase = true) &&
                !isLocalOrPrivateHost(parsed.host ?: return false)
        } catch (e: Exception) {
            false
        }
    }

    fun sanitizeUrlForDisplay(url: String): String {
        return try {
            val parsed = URI(url)
            if (parsed.userInfo != null) {
                URI(
                    parsed.scheme, null, parsed.host, parsed.port,
                    parsed.path, parsed.query, parsed.fragment,
                ).toString().trimEnd('/')
            } else {
                url.trimEnd('/')
            }
        } catch (e: Exception) {
            url.replace(Regex("""//.*@"""), "//[credentials-hidden]@")
        }
    }
}
