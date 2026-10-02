package com.logicedge.opencodemobile.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

class OpenCodeServerManager(private val context: Context) {

    companion object {
        private const val TAG = "OpenCodeServerManager"
        const val OPENCODE_PORT = 4096
        const val OPENCODE_HOST = "127.0.0.1"
        const val OPENCODE_URL = "http://127.0.0.1:4096"
        private const val OPENCODE_CONFIG_DIR = "home/.config/opencode"
        private const val OPENCODE_DATA_DIR = "home/.local/share/opencode"
        private const val OPENCODE_CONFIG_FILE = "opencode.json"
        private const val OPENCODE_AUTH_FILE = "auth.json"
        private const val MARKER_FILE_NAME = ".opencode_installed"
        private const val HEALTH_CHECK_MAX_ATTEMPTS = 30
        private const val HEALTH_CHECK_INTERVAL_MS = 1000L

        private val json = Json { prettyPrint = true }
    }

    private val bootstrapInstaller = BootstrapInstaller(context)
    private val prefixDir = bootstrapInstaller.getPrefixDir()

    private var serverProcess: Process? = null

    suspend fun installOpenCodeCLI(): Result = withContext(Dispatchers.IO) {
        try {
            val markerFile = File(prefixDir, MARKER_FILE_NAME)
            if (markerFile.exists()) {
                Log.i(TAG, "OpenCode CLI already installed (marker found)")
                return@withContext Result.Success
            }
            Log.i(TAG, "Installing OpenCode CLI: npm install -g opencode-ai")
            val processBuilder = ProcessBuilder("$prefixDir/bin/npm", "install", "-g", "opencode-ai")
            processBuilder.environment().putAll(setupEnvironment())
            processBuilder.directory(prefixDir)
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errorOutput = process.inputStream.bufferedReader().readText()
                Log.e(TAG, "npm install failed with exit code $exitCode: $errorOutput")
                return@withContext Result.Error("npm install -g opencode-ai failed (exit $exitCode)")
            }
            markerFile.createNewFile()
            Log.i(TAG, "OpenCode CLI installed successfully")
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install OpenCode CLI", e)
            Result.Error("OpenCode CLI installation failed: ${e.message}")
        }
    }

    suspend fun writeOpenCodeConfig(): Result = withContext(Dispatchers.IO) {
        try {
            val configDir = File(prefixDir, OPENCODE_CONFIG_DIR)
            if (!configDir.mkdirs() && !configDir.exists()) {
                return@withContext Result.Error("Failed to create config directory")
            }
            val configJson = buildJsonObject {
                put("environment", JsonPrimitive("production"))
                put("autoStartServer", JsonPrimitive(true))
                put("serverPort", JsonPrimitive(OPENCODE_PORT))
                put("serverHostname", JsonPrimitive(OPENCODE_HOST))
                put("logLevel", JsonPrimitive("info"))
            }
            File(configDir, OPENCODE_CONFIG_FILE).writeText(json.encodeToString(JsonObject.serializer(), configJson))
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write OpenCode config", e)
            Result.Error("Failed to write OpenCode config: ${e.message}")
        }
    }

    suspend fun setZenApiKey(zenApiKey: String): Result =
        setProviderCredentials("opencode", mapOf("provider" to "opencode", "apiKey" to zenApiKey))

    suspend fun setProviderCredentials(providerName: String, providerData: Map<String, String>): Result =
        withContext(Dispatchers.IO) {
            try {
                val dataDir = File(prefixDir, OPENCODE_DATA_DIR)
                if (!dataDir.mkdirs() && !dataDir.exists()) {
                    return@withContext Result.Error("Failed to create data directory")
                }
                val authFile = File(dataDir, OPENCODE_AUTH_FILE)
                val existing: JsonObject = if (authFile.exists()) {
                    runCatching { json.parseToJsonElement(authFile.readText()).jsonObject }
                        .getOrElse { buildJsonObject { } }
                } else {
                    buildJsonObject { }
                }
                val providers = existing["providers"]?.jsonObject ?: buildJsonObject { }
                val updatedProviders = buildJsonObject {
                    providers.forEach { (k, v) -> put(k, v) }
                    put(
                        providerName,
                        buildJsonObject {
                            providerData.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                        },
                    )
                }
                val merged = buildJsonObject {
                    existing.forEach { (k, v) -> if (k != "providers") put(k, v) }
                    put("providers", updatedProviders)
                }
                authFile.writeText(json.encodeToString(JsonObject.serializer(), merged))
                try {
                    Runtime.getRuntime()
                        .exec(arrayOf("chmod", "600", authFile.absolutePath))
                        .waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set auth.json permissions", e)
                }
                Log.i(TAG, "Provider '$providerName' credentials written")
                Result.Success
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set provider credentials", e)
                Result.Error("Failed to set provider credentials: ${e.message}")
            }
        }

    suspend fun startServer(): Result = withContext(Dispatchers.IO) {
        try {
            if (isServerRunning()) {
                Log.i(TAG, "Server is already running")
                return@withContext Result.Success
            }
            Log.i(TAG, "Starting OpenCode server on $OPENCODE_HOST:$OPENCODE_PORT")
            val processBuilder = ProcessBuilder(
                "$prefixDir/bin/opencode",
                "serve",
                "--hostname", OPENCODE_HOST,
                "--port", OPENCODE_PORT.toString(),
            )
            processBuilder.environment().putAll(setupEnvironment())
            processBuilder.directory(prefixDir)
            processBuilder.redirectErrorStream(true)
            serverProcess = processBuilder.start()
            if (!waitForServerReady()) {
                Log.e(TAG, "Server health check failed after startup")
                return@withContext Result.Error("Server failed to become ready")
            }
            Log.i(TAG, "OpenCode server is ready at $OPENCODE_URL")
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OpenCode server", e)
            Result.Error("Failed to start server: ${e.message}")
        }
    }

    fun isServerRunning(): Boolean {
        return try {
            val connection = URL("$OPENCODE_URL/health").openConnection()
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.connect()
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun waitForServerReady(): Boolean = withContext(Dispatchers.IO) {
        for (attempt in 1..HEALTH_CHECK_MAX_ATTEMPTS) {
            try {
                val connection = URL("$OPENCODE_URL/health").openConnection()
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.connect()
                Log.i(TAG, "Server health check passed on attempt $attempt")
                return@withContext true
            } catch (e: Exception) {
                if (attempt < HEALTH_CHECK_MAX_ATTEMPTS) delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
        Log.e(TAG, "Server health check failed after $HEALTH_CHECK_MAX_ATTEMPTS attempts")
        false
    }

    suspend fun stopServer(): Result = withContext(Dispatchers.IO) {
        try {
            val process = serverProcess
                ?: return@withContext Result.Error("Server not running")
            Log.i(TAG, "Stopping OpenCode server")
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            serverProcess = null
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop server", e)
            Result.Error("Failed to stop server: ${e.message}")
        }
    }

    private fun setupEnvironment(): Map<String, String> = mapOf(
        "PREFIX" to prefixDir.absolutePath,
        "PATH" to "$prefixDir/bin:$prefixDir/sbin:/system/bin:/system/xbin",
        "HOME" to "$prefixDir/home",
        "TMPDIR" to "$prefixDir/tmp",
        "LD_LIBRARY_PATH" to "$prefixDir/lib:$prefixDir/lib64",
        "LANG" to "en_US.UTF-8",
        "LC_ALL" to "en_US.UTF-8",
        "ANDROID_DATA" to context.filesDir.absolutePath,
    )

    sealed class Result {
        data object Success : Result()
        data class Error(val message: String) : Result()
    }
}
