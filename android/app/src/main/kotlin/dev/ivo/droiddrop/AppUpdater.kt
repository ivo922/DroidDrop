package dev.ivo.droiddrop

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Downloads and verifies signed GitHub Release manifests and APKs without extra HTTP dependencies. */
object AppUpdater {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val UPDATES_DIR = "updates"

    fun fetchLatest(
        manifestUrl: String = UpdateConfig.MANIFEST_URL,
        signatureUrl: String = UpdateConfig.SIGNATURE_URL,
        publicKeyBase64: String = UpdateConfig.PUBLIC_KEY_BASE64,
    ): UpdateManifest {
        val manifestBytes = downloadBytes(manifestUrl, MAX_MANIFEST_BYTES)
        val sigText = downloadBytes(signatureUrl, 1024).decodeToString()
        return UpdateVerifier.verifyAndParse(
            manifestBytes = manifestBytes,
            signatureBase64 = sigText,
            publicKeyBase64 = publicKeyBase64,
        )
    }

    fun downloadAndVerify(
        asset: UpdateAsset,
        cacheDir: File,
        versionCode: Long,
        onProgress: (Float) -> Unit,
    ): File {
        val dir = File(cacheDir, UPDATES_DIR).apply { mkdirs() }
        val target = File(dir, UpdatePolicy.apkFileName(versionCode))
        val part = File(dir, "${target.name}.part")
        if (part.exists()) part.delete()

        try {
            val conn = openHttps(asset.url)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var totalRead = 0L
                val expectedTotal = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            totalRead += read
                            if (totalRead > asset.size) {
                                throw IOException("Downloaded APK exceeded expected size.")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            if (expectedTotal > 0) {
                                onProgress((totalRead.toFloat() / expectedTotal).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (totalRead != asset.size) {
                    throw IOException("Downloaded APK size ($totalRead) did not match expected (${asset.size}).")
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(asset.sha256, ignoreCase = true)) {
                    throw UpdateVerificationException("Downloaded APK checksum did not match the signed manifest.")
                }
            } finally {
                conn.disconnect()
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw IOException("Could not save downloaded APK.")
            }
            cleanStaleDownloads(cacheDir, keepFileName = target.name)
            onProgress(1f)
            return target
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    fun cleanStaleDownloads(cacheDir: File, keepFileName: String? = null) {
        val dir = File(cacheDir, UPDATES_DIR)
        dir.listFiles()?.forEach { file ->
            if (file.name != keepFileName) file.delete()
        }
    }

    fun installIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apkFile)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun downloadBytes(url: String, maxBytes: Int): ByteArray {
        val conn = openHttps(url)
        try {
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    if (out.size() + read > maxBytes) throw IOException("Response too large.")
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttps(initialUrl: String): HttpURLConnection {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            if (!UpdatePolicy.isAllowedRedirect(current)) {
                throw IOException("Refused non-HTTPS URL: $current")
            }
            val conn = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without Location header.")
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("Update server returned HTTP $code.")
            }
            return conn
        }
        throw IOException("Too many redirects while fetching update.")
    }
}
