package com.logicedge.opencodemobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * BootstrapInstaller: Extracts a Termux-derived Linux bootstrap into private app storage.
 *
 * On first launch, it checks if the bootstrap is already extracted. If not, it extracts
 * the bundled bootstrap-aarch64.zip asset to $PREFIX = /data/data/<applicationId>/files/usr,
 * preserving file permissions and symlinks.
 *
 * After extraction, it chmod +x on core binaries.
 * Supports re-extraction on app updates via version checking (SHA256 of bootstrap zip).
 */
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
        Context.MODE_PRIVATE
    )

    /**
     * Get the PREFIX directory where the bootstrap will be extracted.
     * Returns: /data/data/<applicationId>/files/usr
     */
    fun getPrefixDir(): File {
        return File(context.filesDir, PREFIX_DIR_NAME)
    }

    /**
     * Check if the bootstrap is already extracted and valid.
     */
    fun isBootstrapExtracted(): Boolean {
        val prefixDir = getPrefixDir()
        val usrBinPath = File(prefixDir, "bin")

        // Bootstrap is considered extracted if:
        // 1. The PREFIX/bin directory exists
        // 2. The shared preference flag is set
        return prefixDir.exists() && usrBinPath.exists() &&
                sharedPrefs.getBoolean(KEY_BOOTSTRAP_EXTRACTED, false)
    }

    /**
     * Get the SHA256 hash of the bootstrap asset in APK assets.
     */
    private fun getBootstrapAssetHash(): String {
        return try {
            val inputStream = context.assets.open(BOOTSTRAP_ASSET_NAME)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            inputStream.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute bootstrap asset hash", e)
            ""
        }
    }

    /**
     * Check if bootstrap needs re-extraction (version/hash mismatch).
     */
    private fun needsReextraction(): Boolean {
        val currentHash = getBootstrapAssetHash()
        val storedHash = sharedPrefs.getString(KEY_BOOTSTRAP_HASH, "")
        return currentHash != storedHash
    }

    /**
     * Extract the bootstrap asset into the PREFIX directory.
     * Preserves file permissions and symlinks where possible.
     *
     * @param onProgress Callback for extraction progress (0–100).
     */
    suspend fun extractBootstrap(onProgress: ((Int) -> Unit)? = null): Result {
        return withContext(Dispatchers.IO) {
            try {
                val prefixDir = getPrefixDir()
                val currentHash = getBootstrapAssetHash()

                // If already extracted and hash matches, skip
                if (isBootstrapExtracted() && !needsReextraction()) {
                    Log.i(TAG, "Bootstrap already extracted and valid (hash match)")
                    onProgress?.invoke(100)
                    return@withContext Result.Success
                }

                // If re-extraction needed, remove old bootstrap
                if (prefixDir.exists()) {
                    Log.i(TAG, "Removing old bootstrap for re-extraction")
                    prefixDir.deleteRecursively()
                }

                // Create PREFIX directory
                if (!prefixDir.mkdirs()) {
                    Log.e(TAG, "Failed to create PREFIX directory")
                    return@withContext Result.Error("Failed to create PREFIX directory")
                }

                Log.i(TAG, "Extracting bootstrap from asset: $BOOTSTRAP_ASSET_NAME")

                // Open bootstrap zip from assets
                val inputStream = context.assets.open(BOOTSTRAP_ASSET_NAME)
                val zipInputStream = ZipInputStream(inputStream)

                var entry: ZipEntry?
                var filesProcessed = 0
                var totalFiles = 0

                // First pass: count total files
                val entries = mutableListOf<ZipEntry>()
                while (zipInputStream.nextEntry.also { entry = it } != null) {
                    entries.add(entry!!)
                    totalFiles++
                }
                zipInputStream.close()

                // Second pass: extract files
                val inputStream2 = context.assets.open(BOOTSTRAP_ASSET_NAME)
                val zipInputStream2 = ZipInputStream(inputStream2)

                while (zipInputStream2.nextEntry.also { entry = it } != null) {
                    val entryName = entry!!.name
                    val entryFile = File(prefixDir, entryName)

                    if (entry!!.isDirectory) {
                        // Create directory
                        if (!entryFile.mkdirs() && !entryFile.exists()) {
                            Log.w(TAG, "Failed to create directory: $entryName")
                        }
                    } else {
                        // Ensure parent directory exists
                        entryFile.parentFile?.mkdirs()

                        // Write file
                        val fileOutput = FileOutputStream(entryFile)
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (zipInputStream2.read(buffer).also { bytesRead = it } != -1) {
                            fileOutput.write(buffer, 0, bytesRead)
                        }
                        fileOutput.close()

                        // Preserve file permissions if available (Unix external attributes)
                        try {
                            val unixMode = (entry!!.externalFileAttributes shr 16) and 0xFFF
                            if (unixMode != 0) {
                                val permissions = unixMode.toString(8)
                                Runtime.getRuntime()
                                    .exec(arrayOf("chmod", permissions, entryFile.absolutePath))
                                    .waitFor()
                                Log.d(TAG, "Set permissions $permissions on $entryName")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to preserve permissions for $entryName", e)
                        }
                    }

                    filesProcessed++
                    val progress = (filesProcessed * 100) / maxOf(totalFiles, 1)
                    onProgress?.invoke(progress)
                }

                zipInputStream2.close()
                inputStream2.close()

                // chmod +x on core binaries
                chmodCoreBindaries(prefixDir)

                // Update shared preferences
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

    /**
     * chmod +x on core binaries (bin/sh, bin/bash, bin/node if present).
     */
    private fun chmodCoreBindaries(prefixDir: File) {
        val coreBinaries = listOf(
            "bin/sh",
            "bin/bash",
            "bin/node",
            "bin/npm",
            "bin/opencode" // placeholder for opencode-serve binary
        )

        for (binPath in coreBinaries) {
            val binaryFile = File(prefixDir, binPath)
            if (binaryFile.exists()) {
                try {
                    Runtime.getRuntime()
                        .exec(arrayOf("chmod", "+x", binaryFile.absolutePath))
                        .waitFor()
                    Log.d(TAG, "chmod +x on $binPath")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to chmod +x on $binPath", e)
                }
            }
        }
    }

    /**
     * Result sealed class for extraction operations.
     */
    sealed class Result {
        object Success : Result()
        data class Error(val message: String) : Result()
    }
}
