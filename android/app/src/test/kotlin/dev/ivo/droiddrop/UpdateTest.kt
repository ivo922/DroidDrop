package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateTest {
    private val sampleSha = "a".repeat(64)

    private data class SignedSample(val bytes: ByteArray, val sigBase64: String, val pubBase64: String)

    private fun makeSigned(
        version: String = "0.4.0",
        versionCode: Long = 400,
        notes: String = "- New feature",
        macUrl: String = "https://github.com/ivo922/DroidDrop/releases/download/v0.4.0/DroidDrop-0.4.0-macOS.zip",
        androidUrl: String = "https://github.com/ivo922/DroidDrop/releases/download/v0.4.0/DroidDrop-0.4.0.apk",
        sha256: String = sampleSha,
        size: Long = 2048,
    ): SignedSample {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val spki = kp.public.encoded
        assertEquals(44, spki.size)
        val rawPub = spki.copyOfRange(12, 44)
        val json = """
            {
              "version": "$version",
              "versionCode": $versionCode,
              "notes": "$notes",
              "macos": {"url": "$macUrl", "sha256": "$sha256", "size": $size},
              "android": {"url": "$androidUrl", "sha256": "$sha256", "size": $size}
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("Ed25519").run {
            initSign(kp.private)
            update(json)
            sign()
        }
        assertEquals(64, sig.size)
        return SignedSample(
            bytes = json,
            sigBase64 = Base64.getEncoder().encodeToString(sig),
            pubBase64 = Base64.getEncoder().encodeToString(rawPub),
        )
    }

    @Test
    fun verifiesValidSignedManifest() {
        val sample = makeSigned()
        val manifest = UpdateVerifier.verifyAndParse(sample.bytes, sample.sigBase64 + "\n", sample.pubBase64)
        assertEquals("0.4.0", manifest.version)
        assertEquals(400L, manifest.versionCode)
        assertEquals("- New feature", manifest.notes)
        assertEquals(2048L, manifest.android.size)
    }

    @Test
    fun rejectsTamperedManifest() {
        val sample = makeSigned()
        val tampered = sample.bytes + byteArrayOf(' '.code.toByte())
        assertThrows(UpdateVerificationException::class.java) {
            UpdateVerifier.verifyAndParse(tampered, sample.sigBase64, sample.pubBase64)
        }
    }

    @Test
    fun rejectsWrongPublicKey() {
        val sample = makeSigned()
        val other = makeSigned()
        assertThrows(UpdateVerificationException::class.java) {
            UpdateVerifier.verifyAndParse(sample.bytes, sample.sigBase64, other.pubBase64)
        }
    }

    @Test
    fun rejectsOffOriginUrlBadHashAndZeroSize() {
        val badUrl = makeSigned(androidUrl = "https://evil.example.com/DroidDrop.apk")
        assertThrows(UpdateVerificationException::class.java) {
            UpdateVerifier.verifyAndParse(badUrl.bytes, badUrl.sigBase64, badUrl.pubBase64)
        }

        val badHash = makeSigned(sha256 = "not-a-hash")
        assertThrows(UpdateVerificationException::class.java) {
            UpdateVerifier.verifyAndParse(badHash.bytes, badHash.sigBase64, badHash.pubBase64)
        }

        val zeroSize = makeSigned(size = 0)
        assertThrows(UpdateVerificationException::class.java) {
            UpdateVerifier.verifyAndParse(zeroSize.bytes, zeroSize.sigBase64, zeroSize.pubBase64)
        }
    }

    @Test
    fun committedPublicKeyIsValid32ByteEd25519Key() {
        val raw = Base64.getDecoder().decode(UpdateConfig.PUBLIC_KEY_BASE64)
        assertEquals(32, raw.size)
        // Verifying with a bogus signature must fail cleanly as mismatch (not invalid key format).
        val ex = assertThrows(UpdateVerificationException::class.java) {
            UpdateVerifier.verifyAndParse(
                "{}".toByteArray(),
                Base64.getEncoder().encodeToString(ByteArray(64)),
                UpdateConfig.PUBLIC_KEY_BASE64,
            )
        }
        assertTrue(ex.message?.contains("signature") == true)
    }

    @Test
    fun updatePolicyHandlesVersionThrottleAndDismissal() {
        assertTrue(UpdatePolicy.isNewer(400, 300))
        assertFalse(UpdatePolicy.isNewer(300, 300))
        assertFalse(UpdatePolicy.isNewer(200, 300))

        val now = 1_800_000_000_000L
        assertTrue(UpdatePolicy.shouldAutoCheck(null, now))
        assertFalse(UpdatePolicy.shouldAutoCheck(now - 3_600_000L, now))
        assertTrue(UpdatePolicy.shouldAutoCheck(now - 86_400_000L, now))

        assertFalse(UpdatePolicy.shouldShowBanner(400, dismissedVersionCode = 400, manual = false))
        assertTrue(UpdatePolicy.shouldShowBanner(400, dismissedVersionCode = 400, manual = true))
        assertTrue(UpdatePolicy.shouldShowBanner(500, dismissedVersionCode = 400, manual = false))

        assertEquals("DroidDrop-400.apk", UpdatePolicy.apkFileName(400))
        assertTrue(UpdatePolicy.isAllowedRedirect("https://objects.githubusercontent.com/file.apk"))
        assertFalse(UpdatePolicy.isAllowedRedirect("http://objects.githubusercontent.com/file.apk"))
    }
}
