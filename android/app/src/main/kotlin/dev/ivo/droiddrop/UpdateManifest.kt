package dev.ivo.droiddrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class UpdateAsset(val url: String, val sha256: String, val size: Long)

data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val notes: String,
    val macos: UpdateAsset,
    val android: UpdateAsset,
)

class UpdateVerificationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Verifies detached Ed25519 signatures over raw `update.json` bytes before parsing JSON. */
object UpdateVerifier {
    // Fixed ASN.1 SubjectPublicKeyInfo prefix for a 32-byte raw Ed25519 public key.
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    fun verifyAndParse(
        manifestBytes: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String = UpdateConfig.PUBLIC_KEY_BASE64,
        allowedDownloadPrefix: String = UpdateConfig.ALLOWED_DOWNLOAD_PREFIX,
    ): UpdateManifest {
        val rawKey = decodeBase64(publicKeyBase64, expectedSize = 32, label = "public key")
        val signature = decodeBase64(signatureBase64, expectedSize = 64, label = "signature")

        val valid = try {
            val publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + rawKey))
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(manifestBytes)
                verify(signature)
            }
        } catch (e: Exception) {
            throw UpdateVerificationException("Could not verify update signature.", e)
        }
        if (!valid) {
            throw UpdateVerificationException("Update manifest signature could not be verified.")
        }

        val root = try {
            Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
        } catch (e: Exception) {
            throw UpdateVerificationException("Update manifest is not valid JSON.", e)
        }

        val version = root.requiredString("version").trim()
        if (version.isEmpty()) throw UpdateVerificationException("Update manifest version is empty.")
        val versionCode = root.requiredPositiveLong("versionCode")
        val notes = root.optionalString("notes")
        val macos = parseAsset(root.requiredObject("macos"), allowedDownloadPrefix)
        val android = parseAsset(root.requiredObject("android"), allowedDownloadPrefix)
        return UpdateManifest(version, versionCode, notes, macos, android)
    }

    private fun parseAsset(obj: JsonObject, allowedDownloadPrefix: String): UpdateAsset {
        val url = obj.requiredString("url")
        if (!url.startsWith(allowedDownloadPrefix) || !url.startsWith("https://")) {
            throw UpdateVerificationException("Update download URL is not allowed: $url")
        }
        val sha256 = obj.requiredString("sha256")
        if (!isHexSha256(sha256)) {
            throw UpdateVerificationException("Update manifest hash is invalid.")
        }
        val size = obj.requiredPositiveLong("size")
        return UpdateAsset(url = url, sha256 = sha256.lowercase(), size = size)
    }

    private fun decodeBase64(value: String, expectedSize: Int, label: String): ByteArray {
        val decoded = try {
            Base64.getDecoder().decode(value.trim())
        } catch (e: IllegalArgumentException) {
            throw UpdateVerificationException("Update $label is not valid base64.", e)
        }
        if (decoded.size != expectedSize) {
            throw UpdateVerificationException("Update $label must be $expectedSize bytes.")
        }
        return decoded
    }

    private fun JsonObject.requiredObject(key: String): JsonObject =
        (this[key] as? JsonObject) ?: throw UpdateVerificationException("Update manifest missing '$key'.")

    private fun JsonObject.requiredString(key: String): String {
        val primitive = this[key] as? JsonPrimitive
            ?: throw UpdateVerificationException("Update manifest missing '$key'.")
        if (!primitive.isString) throw UpdateVerificationException("Update manifest '$key' must be a string.")
        return primitive.content
    }

    private fun JsonObject.optionalString(key: String): String {
        val element = this[key] ?: return ""
        val primitive = element as? JsonPrimitive
            ?: throw UpdateVerificationException("Update manifest '$key' must be a string.")
        if (!primitive.isString) throw UpdateVerificationException("Update manifest '$key' must be a string.")
        return primitive.content
    }

    private fun JsonObject.requiredPositiveLong(key: String): Long {
        val value = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: throw UpdateVerificationException("Update manifest missing numeric '$key'.")
        if (value <= 0) throw UpdateVerificationException("Update manifest '$key' must be positive.")
        return value
    }

    private fun isHexSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
