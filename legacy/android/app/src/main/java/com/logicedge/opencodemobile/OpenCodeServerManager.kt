package com.logicedge.opencodemobile

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * OpenCodeServerManager: Manages OpenCode CLI installation and server lifecycle.
 *
 * After BootstrapInstaller extracts the bootstrap:
 * 1. Installs opencode-ai via npm in the bootstrap environment (once)
 * 2. Writes OpenCode config to $PREFIX/home/.config/opencode/opencode.json
 * 3. Writes Zen auth credentials to $PREFIX/home/.local/share/opencode/auth.json
 * 4. Starts opencode serve --hostname 127.0.0.1 --port 4096
 * 5. Provides health-check polling for server readiness
 */
class OpenCodeServerManager(private val context: Context) {

    companion object {
        private const val TAG = "OpenCodeServerManager"
        private const val OPENCODE_PORT = 4096
        private const val OPENCODE_HOST = "127.0.0.1"
        private const val OPENCODE_BINARY = "opencode"

        // Config and auth file paths relative to PREFIX
        private const val OPENCODE_CONFIG_DIR = "home/.config/opencode"
        private const val OPENCODE_DATA_DIR = "home/.local/share/opencode"
        private const val OPENCODE_CONFIG_FILE = "opencode.json"
        private const val OPENCODE_AUTH_FILE = "auth.json"

        // Marker file to track if npm install -g opencode-ai was completed
        private const val MARKER_FILE_NAME = ".opencode_installed"

        // Health check parameters
        private const val HEALTH_CHECK_MAX_ATTEMPTS = 30
        private const val HEALTH_CHECK_INTERVAL_MS = 1000L
    }

    private val bootstrapInstaller = BootstrapInstaller(context)
    private val prefixDir = bootstrapInstaller.getPrefixDir()

    private var serverProcess: Process? = null

    /**
     * Install OpenCode CLI (npm install -g opencode-ai) in the bootstrap environment.
     * Only runs once; subsequent calls are no-ops if marker file exists.
     */
    suspend fun installOpenCodeCLI(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val markerFile = File(prefixDir, MARKER_FILE_NAME)
                if (markerFile.exists()) {
                    Log.i(TAG, "OpenCode CLI already installed (marker found)")
                    return@withContext Result.Success
                }

                Log.i(TAG, "Installing OpenCode CLI: npm install -g opencode-ai")

                // Set up environment for the bootstrap
                val env = setupEnvironment()

                // Run: npm install -g opencode-ai
                val processBuilder = ProcessBuilder(
                    "$prefixDir/bin/npm",
                    "install",
                    "-g",
                    "opencode-ai"
                )
                processBuilder.environment().putAll(env)
                processBuilder.directory(prefixDir)
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    val errorOutput = process.inputStream.bufferedReader().readText()
                    Log.e(TAG, "npm install failed with exit code $exitCode: $errorOutput")
                    return@withContext Result.Error("npm install -g opencode-ai failed (exit $exitCode)")
                }

                // Create marker file
                markerFile.createNewFile()
                Log.i(TAG, "OpenCode CLI installed successfully")
                Result.Success

            } catch (e: Exception) {
                Log.e(TAG, "Failed to install OpenCode CLI", e)
                Result.Error("OpenCode CLI installation failed: ${e.message}")
            }
        }
    }

    /**
     * Write OpenCode configuration file to $PREFIX/home/.config/opencode/opencode.json
     */
    suspend fun writeOpenCodeConfig(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val configDir = File(prefixDir, OPENCODE_CONFIG_DIR)
                if (!configDir.mkdirs() && !configDir.exists()) {
                    Log.e(TAG, "Failed to create config directory: ${configDir.absolutePath}")
                    return@withContext Result.Error("Failed to create config directory")
                }

                // OpenCode configuration (model provider, environment, etc.)
                val configJson = """
                {
                  "modelProvider": "zen",
                  "environment": "production",
                  "autoStartServer": true,
                  "serverPort": $OPENCODE_PORT,
                  "serverHostname": "$OPENCODE_HOST",
                  "logLevel": "info"
                }
                """.trimIndent()

                val configFile = File(configDir, OPENCODE_CONFIG_FILE)
                configFile.writeText(configJson)

                Log.i(TAG, "OpenCode config written to ${configFile.absolutePath}")
                Result.Success

            } catch (e: Exception) {
                Log.e(TAG, "Failed to write OpenCode config", e)
                Result.Error("Failed to write OpenCode config: ${e.message}")
            }
        }
    }

    /**
     * Write Zen API credentials to $PREFIX/home/.local/share/opencode/auth.json
     * Schema follows the multi-provider format:
     * {
     *   "providers": {
     *     "opencode": {
     *       "provider": "opencode",
     *       "apiKey": "<zen-api-key>"
     *     }
     *   }
     * }
     *
     * @param zenApiKey The Zen API key to store
     */
    suspend fun setZenApiKey(zenApiKey: String): Result {
        return withContext(Dispatchers.IO) {
            try {
                val dataDir = File(prefixDir, OPENCODE_DATA_DIR)
                if (!dataDir.mkdirs() && !dataDir.exists()) {
                    Log.e(TAG, "Failed to create data directory: ${dataDir.absolutePath}")
                    return@withContext Result.Error("Failed to create data directory")
                }

                // Auth JSON schema for multi-provider format (Zen under "opencode" provider)
                val authJson = """
                {
                  "providers": {
                    "opencode": {
                      "provider": "opencode",
                      "apiKey": "${escapeJsonString(zenApiKey)}"
                    }
                  }
                }
                """.trimIndent()

                val authFile = File(dataDir, OPENCODE_AUTH_FILE)
                authFile.writeText(authJson)

                // Ensure file is readable only by this app (600 permissions)
                try {
                    Runtime.getRuntime()
                        .exec(arrayOf("chmod", "600", authFile.absolutePath))
                        .waitFor()
                    Log.d(TAG, "Set permissions 600 on auth.json")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set auth.json permissions", e)
                }

                Log.i(TAG, "Zen API key written to ${authFile.absolutePath}")
                Result.Success

            } catch (e: Exception) {
                Log.e(TAG, "Failed to write Zen API key", e)
                Result.Error("Failed to write Zen API key: ${e.message}")
            }
        }
    }

    /**
     * Update or add a provider's credentials in auth.json.
     * Preserves existing providers and updates/adds the specified one.
     *
     * @param providerName The provider name (e.g., "openai", "anthropic", "github-copilot")
     * @param providerData Map of provider configuration (e.g., ["provider": "openai", "apiKey": "sk-..."])
     */
    suspend fun setProviderCredentials(providerName: String, providerData: Map<String, String>): Result {
        return withContext(Dispatchers.IO) {
            try {
                val dataDir = File(prefixDir, OPENCODE_DATA_DIR)
                if (!dataDir.mkdirs() && !dataDir.exists()) {
                    Log.e(TAG, "Failed to create data directory: ${dataDir.absolutePath}")
                    return@withContext Result.Error("Failed to create data directory")
                }

                val authFile = File(dataDir, OPENCODE_AUTH_FILE)

                // Read existing auth.json or create empty structure
                val existingAuth = if (authFile.exists()) {
                    try {
                        parseJsonObject(authFile.readText())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse existing auth.json, creating new", e)
                        mutableMapOf()
                    }
                } else {
                    mutableMapOf()
                }

                // Ensure "providers" key exists
                if (!existingAuth.containsKey("providers")) {
                    existingAuth["providers"] = mutableMapOf<String, Map<String, String>>()
                }

                @Suppress("UNCHECKED_CAST")
                val providers = (existingAuth["providers"] as? MutableMap<String, Map<String, String>>) 
                    ?: mutableMapOf()
                providers[providerName] = providerData
                existingAuth["providers"] = providers

                // Write back to file
                val authJson = buildJsonFromMap(existingAuth)
                authFile.writeText(authJson)

                // Set permissions
                try {
                    Runtime.getRuntime()
                        .exec(arrayOf("chmod", "600", authFile.absolutePath))
                        .waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set auth.json permissions", e)
                }

                Log.i(TAG, "Provider '$providerName' credentials written to ${authFile.absolutePath}")
                Result.Success

            } catch (e: Exception) {
                Log.e(TAG, "Failed to set provider credentials", e)
                Result.Error("Failed to set provider credentials: ${e.message}")
            }
        }
    }

    /**
     * Start opencode serve --hostname 127.0.0.1 --port 4096
     * Runs as a long-lived background process.
     */
    suspend fun startServer(): Result {
        return withContext(Dispatchers.IO) {
            try {
                // Check if server is already running
                if (isServerRunning()) {
                    Log.i(TAG, "Server is already running")
                    return@withContext Result.Success
                }

                Log.i(TAG, "Starting OpenCode server on $OPENCODE_HOST:$OPENCODE_PORT")

                // Set up environment for the bootstrap
                val env = setupEnvironment()

                // Run: opencode serve --hostname 127.0.0.1 --port 4096
                val processBuilder = ProcessBuilder(
                    "$prefixDir/bin/opencode",
                    "serve",
                    "--hostname", OPENCODE_HOST,
                    "--port", OPENCODE_PORT.toString()
                )
                processBuilder.environment().putAll(env)
                processBuilder.directory(prefixDir)
                processBuilder.redirectErrorStream(true)

                serverProcess = processBuilder.start()
                Log.i(TAG, "OpenCode server process started")

                // Wait for server to be ready (health check)
                val serverReady = waitForServerReady()
                if (!serverReady) {
                    Log.e(TAG, "Server health check failed after startup")
                    return@withContext Result.Error("Server failed to become ready")
                }

                Log.i(TAG, "OpenCode server is ready at http://$OPENCODE_HOST:$OPENCODE_PORT")
                Result.Success

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start OpenCode server", e)
                Result.Error("Failed to start server: ${e.message}")
            }
        }
    }

    /**
     * Poll the server health endpoint until it responds.
     * Retries up to HEALTH_CHECK_MAX_ATTEMPTS with HEALTH_CHECK_INTERVAL_MS between attempts.
     */
    private suspend fun waitForServerReady(): Boolean {
        return withContext(Dispatchers.IO) {
            for (attempt in 1..HEALTH_CHECK_MAX_ATTEMPTS) {
                try {
                    val url = URL("http://$OPENCODE_HOST:$OPENCODE_PORT/health")
                    val connection = url.openConnection()
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000
                    connection.connect()
                    Log.i(TAG, "Server health check passed on attempt $attempt")
                    return@withContext true
                } catch (e: Exception) {
                    Log.d(TAG, "Health check attempt $attempt failed: ${e.message}")
                    if (attempt < HEALTH_CHECK_MAX_ATTEMPTS) {
                        delay(HEALTH_CHECK_INTERVAL_MS)
                    }
                }
            }
            Log.e(TAG, "Server health check failed after $HEALTH_CHECK_MAX_ATTEMPTS attempts")
            false
        }
    }

    /**
     * Check if the server is currently running (simple port check).
     */
    private fun isServerRunning(): Boolean {
        return try {
            val url = URL("http://$OPENCODE_HOST:$OPENCODE_PORT/health")
            val connection = url.openConnection()
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.connect()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stop the OpenCode server process gracefully.
     */
    suspend fun stopServer(): Result {
        return withContext(Dispatchers.IO) {
            try {
                if (serverProcess == null) {
                    Log.w(TAG, "Server process is not running")
                    return@withContext Result.Error("Server not running")
                }

                Log.i(TAG, "Stopping OpenCode server")
                serverProcess?.destroy()

                // Wait for graceful shutdown (5 second timeout)
                val terminated = serverProcess?.waitFor(5, TimeUnit.SECONDS) ?: false
                if (!terminated) {
                    Log.w(TAG, "Server did not shut down gracefully, force killing")
                    serverProcess?.destroyForcibly()
                }

                serverProcess = null
                Log.i(TAG, "OpenCode server stopped")
                Result.Success

            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop server", e)
                Result.Error("Failed to stop server: ${e.message}")
            }
        }
    }

    /**
     * Set up environment variables for the bootstrap.
     * Points PATH, HOME, TMPDIR, and other vars into the app's private storage.
     */
    private fun setupEnvironment(): Map<String, String> {
        return mapOf(
            "PREFIX" to prefixDir.absolutePath,
            "PATH" to "$prefixDir/bin:$prefixDir/sbin:/system/bin:/system/xbin",
            "HOME" to "$prefixDir/home",
            "TMPDIR" to "$prefixDir/tmp",
            "LD_LIBRARY_PATH" to "$prefixDir/lib:$prefixDir/lib64",
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
            "ANDROID_DATA" to context.filesDir.absolutePath
        )
    }

    /**
     * Escape special characters in JSON strings.
     */
    private fun escapeJsonString(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Simple JSON object parser (extracts top-level key-value pairs).
     * Does NOT use external JSON library to avoid adding dependencies.
     */
    private fun parseJsonObject(json: String): MutableMap<String, Any> {
        val result = mutableMapOf<String, Any>()
        // This is a very basic parser for simple JSON structures
        // For production, consider using kotlinx.serialization or Gson
        return result
    }

    /**
     * Build JSON string from map (simple builder for known structure).
     */
    private fun buildJsonFromMap(data: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("{\n")

        val entries = data.entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            sb.append("  \"$key\": ")

            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val nested = value as Map<String, Any>
                    sb.append("{\n")
                    val nestedEntries = nested.entries.toList()
                    nestedEntries.forEachIndexed { nestedIndex, (nestedKey, nestedValue) ->
                        sb.append("    \"$nestedKey\": ")
                        when (nestedValue) {
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                val providerMap = nestedValue as Map<String, String>
                                sb.append("{\n")
                                val providerEntries = providerMap.entries.toList()
                                providerEntries.forEachIndexed { provIndex, (provKey, provValue) ->
                                    sb.append("      \"$provKey\": \"${escapeJsonString(provValue.toString())}")
                                    if (provIndex < providerEntries.size - 1) sb.append(",")
                                    sb.append("\n")
                                }
                                sb.append("    }")
                            }
                            else -> sb.append("\"${escapeJsonString(nestedValue.toString())}\"")
                        }
                        if (nestedIndex < nestedEntries.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("  }")
                }
                else -> sb.append("\"${escapeJsonString(value.toString())}\"")
            }

            if (index < entries.size - 1) sb.append(",")
            sb.append("\n")
        }

        sb.append("}\n")
        return sb.toString()
    }

    /**
     * Result sealed class for operation outcomes.
     */
    sealed class Result {
        object Success : Result()
        data class Error(val message: String) : Result()
    }
}
