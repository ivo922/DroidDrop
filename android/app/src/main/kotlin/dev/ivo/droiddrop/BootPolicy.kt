package dev.ivo.droiddrop

import android.content.Intent

object BootPolicy {
    /**
     * True after boot or an app update when a Mac is paired and LAN access is granted.
     * LOCKED_BOOT_COMPLETED is not handled: the pairing lives in credential-encrypted storage.
     */
    fun shouldStart(action: String?, paired: Boolean, localNetworkAllowed: Boolean): Boolean =
        (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
            paired && localNetworkAllowed
}
