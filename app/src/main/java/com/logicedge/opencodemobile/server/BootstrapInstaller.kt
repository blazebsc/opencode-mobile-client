package com.logicedge.opencodemobile.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class BootstrapInstaller(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapInstaller"
        private const val BOOTSTRAP_ASSET_NAME = "bootstrap-aarch64.zip"
        private const val SHARED_PREFS_NAME = "bootstrap_prefs"
        private const val KEY_BOOTSTRAP_HASH = "bootstrap_hash"
        private const val KEY_BOOTSTRAP_EXTRACTED = "bootstrap_extracted"
        private const val KEY_BOOTSTRAP_VERSION = "bootstrap_version"
        private const val PREFIX_DIR_NAME = "usr"
    }

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(
        SHARED_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun getPrefixDir(): File = File(context.filesDir, PREFIX_DIR_NAME)

    fun hasBootstrapAsset(): Boolean = runCatching {
        context.assets.open(BOOTSTRAP_ASSET_NAME).close()
        true
    }.getOrDefault(false)

    fun isBootstrapExtracted(): Boolean {
        val prefixDir = getPrefixDir()
        return prefixDir.exists() && File(prefixDir, "bin").exists() &&
            sharedPrefs.getBoolean(KEY_BOOTSTRAP_EXTRACTED, false)
    }

    private fun getBootstrapAssetHash(): String {
        return try {
            context.assets.open(BOOTSTRAP_ASSET_NAME).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute bootstrap asset hash", e)
            ""
        }
    }

    private fun needsReextraction(): Boolean {
        val currentHash = getBootstrapAssetHash()
        val storedHash = sharedPrefs.getString(KEY_BOOTSTRAP_HASH, "")
        return currentHash != storedHash
    }

    suspend fun extractBootstrap(onProgress: ((Int) -> Unit)? = null): Result {
        return withContext(Dispatchers.IO) {
            try {
                val prefixDir = getPrefixDir()
                val currentHash = getBootstrapAssetHash()

                if (isBootstrapExtracted() && !needsReextraction()) {
                    Log.i(TAG, "Bootstrap already extracted and valid (hash match)")
                    onProgress?.invoke(100)
                    return@withContext Result.Success
                }

                if (prefixDir.exists()) {
                    Log.i(TAG, "Removing old bootstrap for re-extraction")
                    prefixDir.deleteRecursively()
                }

                if (!prefixDir.mkdirs()) {
                    Log.e(TAG, "Failed to create PREFIX directory")
                    return@withContext Result.Error("Failed to create PREFIX directory")
                }

                Log.i(TAG, "Extracting bootstrap from asset: $BOOTSTRAP_ASSET_NAME")

                val entries = mutableListOf<String>()
                context.assets.open(BOOTSTRAP_ASSET_NAME).use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry: ZipEntry?
                        while (zip.nextEntry.also { entry = it } != null) {
                            entries.add(entry!!.name)
                        }
                    }
                }

                context.assets.open(BOOTSTRAP_ASSET_NAME).use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry: ZipEntry?
                        var filesProcessed = 0
                        while (zip.nextEntry.also { entry = it } != null) {
                            val name = entry!!.name
                            val outFile = File(prefixDir, name)
                            if (entry!!.isDirectory) {
                                if (!outFile.mkdirs() && !outFile.exists()) {
                                    Log.w(TAG, "Failed to create directory: $name")
                                }
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (zip.read(buffer).also { bytesRead = it } != -1) {
                                        out.write(buffer, 0, bytesRead)
                                    }
                                }
                                // Note: java.util.zip exposes no Unix mode bits, so
                                // executable bits are applied in chmodCoreBinaries()
                                // below instead of per-entry permission restore.
                            }
                            filesProcessed++
                            onProgress?.invoke((filesProcessed * 100) / maxOf(entries.size, 1))
                        }
                    }
                }

                chmodCoreBinaries(prefixDir)

                sharedPrefs.edit().apply {
                    putString(KEY_BOOTSTRAP_HASH, currentHash)
                    putBoolean(KEY_BOOTSTRAP_EXTRACTED, true)
                    putLong(KEY_BOOTSTRAP_VERSION, System.currentTimeMillis())
                    apply()
                }

                Log.i(TAG, "Bootstrap extraction completed successfully")
                onProgress?.invoke(100)
                Result.Success
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap extraction failed", e)
                Result.Error("Bootstrap extraction failed: ${e.message}")
            }
        }
    }

    private fun chmodCoreBinaries(prefixDir: File) {
        listOf("bin/sh", "bin/bash", "bin/node", "bin/npm", "bin/opencode").forEach { binPath ->
            val binaryFile = File(prefixDir, binPath)
            if (binaryFile.exists()) {
                try {
                    Runtime.getRuntime()
                        .exec(arrayOf("chmod", "+x", binaryFile.absolutePath))
                        .waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to chmod +x on $binPath", e)
                }
            }
        }
    }

    sealed class Result {
        data object Success : Result()
        data class Error(val message: String) : Result()
    }
}
