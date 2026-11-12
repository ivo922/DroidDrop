package dev.ivo.droiddrop

/** Decides whether a share or pick may be queued. Pure, so it can be unit tested. */
object SendGate {
    /** Null when sending may proceed, else the toast to show. */
    fun refusal(paired: Boolean, localNetworkAllowed: Boolean, fileCount: Int): String? = when {
        !paired -> "Pair DroidDrop with your Mac first."
        !localNetworkAllowed -> "Open DroidDrop to allow local network access."
        fileCount <= 0 -> "Nothing to send."
        else -> null
    }
}
