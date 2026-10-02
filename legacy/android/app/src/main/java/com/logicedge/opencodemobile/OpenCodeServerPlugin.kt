package com.logicedge.opencodemobile

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * OpenCodeServerPlugin: Capacitor bridge for OpenCode server management.
 *
 * Exposes OpenCodeServerManager and BootstrapInstaller functionality to Vue/TypeScript.
 * Methods:
 * - setZenApiKey(key: string) -> Result
 * - checkLocalServerHealth() -> { healthy: boolean, url: string }
 * - startLocalServer() -> Result
 * - stopLocalServer() -> Result
 */
@CapacitorPlugin(name = "OpenCodeServer")
class OpenCodeServerPlugin : Plugin() {

    private lateinit var serverManager: OpenCodeServerManager
    private lateinit var bootstrapInstaller: BootstrapInstaller

    override fun load() {
        serverManager = OpenCodeServerManager(context)
        bootstrapInstaller = BootstrapInstaller(context)
        android.util.Log.i("OpenCodeServerPlugin", "Plugin loaded")
    }

    /**
     * Set the Zen API key (writes to auth.json via OpenCodeServerManager).
     *
     * @call.getstring key - The Zen API key
     */
    @com.getcapacitor.annotation.CapacitorPluginMethod()
    fun setZenApiKey(call: PluginCall) {
        val key = call.getString("key") ?: run {
            call.reject("Missing 'key' parameter")
            return
        }

        if (key.isEmpty()) {
            call.reject("API key cannot be empty")
            return
        }

        bridge.saveCall(call)
        bridge.execute({
            bridge.getActivity()!!.runOnUiThread {
                try {
                    val result = serverManager.setZenApiKey(key)
                    when (result) {
                        is OpenCodeServerManager.Result.Success -> {
                            val ret = JSObject()
                            ret.put("success", true)
                            ret.put("message", "Zen API key set successfully")
                            call.success(ret)
                        }
                        is OpenCodeServerManager.Result.Error -> {
                            call.reject(result.message)
                        }
                    }
                } catch (e: Exception) {
                    call.reject("Error setting Zen API key: ${e.message}")
                }
            }
        })
    }

    /**
     * Check if the local server is healthy and responding.
     *
     * Returns: { healthy: boolean, url: "http://127.0.0.1:4096" }
     */
    @com.getcapacitor.annotation.CapacitorPluginMethod()
    fun checkLocalServerHealth(call: PluginCall) {
        bridge.saveCall(call)
        bridge.execute({
            try {
                val url = java.net.URL("http://127.0.0.1:4096/health")
                val connection = url.openConnection()
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.connect()

                val ret = JSObject()
                ret.put("healthy", true)
                ret.put("url", "http://127.0.0.1:4096")
                call.success(ret)
            } catch (e: Exception) {
                val ret = JSObject()
                ret.put("healthy", false)
                ret.put("url", "http://127.0.0.1:4096")
                ret.put("error", e.message)
                call.success(ret)
            }
        })
    }

    /**
     * Start the local OpenCode server (via foreground service).
     *
     * Returns: { success: boolean, message: string }
     */
    @com.getcapacitor.annotation.CapacitorPluginMethod()
    fun startLocalServer(call: PluginCall) {
        bridge.saveCall(call)
        bridge.execute({
            bridge.getActivity()!!.runOnUiThread {
                try {
                    val intent = android.content.Intent(context, OpenCodeForegroundService::class.java)
                    intent.action = "com.logicedge.opencodemobile.START_SERVER"
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }

                    val ret = JSObject()
                    ret.put("success", true)
                    ret.put("message", "Server service started")
                    call.success(ret)
                } catch (e: Exception) {
                    call.reject("Error starting server: ${e.message}")
                }
            }
        })
    }

    /**
     * Stop the local OpenCode server.
     *
     * Returns: { success: boolean, message: string }
     */
    @com.getcapacitor.annotation.CapacitorPluginMethod()
    fun stopLocalServer(call: PluginCall) {
        bridge.saveCall(call)
        bridge.execute({
            bridge.getActivity()!!.runOnUiThread {
                try {
                    val intent = android.content.Intent(context, OpenCodeForegroundService::class.java)
                    intent.action = "com.logicedge.opencodemobile.STOP_SERVER"
                    context.startService(intent)

                    val ret = JSObject()
                    ret.put("success", true)
                    ret.put("message", "Server stop requested")
                    call.success(ret)
                } catch (e: Exception) {
                    call.reject("Error stopping server: ${e.message}")
                }
            }
        })
    }

    /**
     * Get bootstrap extraction status.
     *
     * Returns: { extracted: boolean, prefixPath: string }
     */
    @com.getcapacitor.annotation.CapacitorPluginMethod()
    fun getBootstrapStatus(call: PluginCall) {
        try {
            val ret = JSObject()
            ret.put("extracted", bootstrapInstaller.isBootstrapExtracted())
            ret.put("prefixPath", bootstrapInstaller.getPrefixDir().absolutePath)
            call.success(ret)
        } catch (e: Exception) {
            call.reject("Error getting bootstrap status: ${e.message}")
        }
    }
}
