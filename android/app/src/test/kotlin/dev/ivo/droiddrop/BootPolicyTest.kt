package dev.ivo.droiddrop

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BootPolicyTest {
    @Test
    fun startsAfterBootAndUpdateWhenPaired() {
        assertTrue(BootPolicy.shouldStart(Intent.ACTION_BOOT_COMPLETED, paired = true, localNetworkAllowed = true))
        assertTrue(BootPolicy.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, paired = true, localNetworkAllowed = true))
    }

    @Test
    fun staysStoppedWhenUnpairedOrLanDenied() {
        assertFalse(BootPolicy.shouldStart(Intent.ACTION_BOOT_COMPLETED, paired = false, localNetworkAllowed = true))
        assertFalse(BootPolicy.shouldStart(Intent.ACTION_BOOT_COMPLETED, paired = true, localNetworkAllowed = false))
    }

    @Test
    fun ignoresOtherActions() {
        assertFalse(BootPolicy.shouldStart(Intent.ACTION_LOCKED_BOOT_COMPLETED, paired = true, localNetworkAllowed = true))
        assertFalse(BootPolicy.shouldStart(null, paired = true, localNetworkAllowed = true))
    }
}
