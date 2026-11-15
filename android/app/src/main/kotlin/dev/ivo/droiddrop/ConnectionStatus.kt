package dev.ivo.droiddrop

import kotlinx.coroutines.flow.MutableStateFlow

sealed interface ConnectionState {
    data object Unpaired : ConnectionState
    data class Pairing(val macName: String) : ConnectionState
    data class Searching(val macName: String) : ConnectionState
    data class Connected(val macName: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

fun ConnectionState.describe(): String = when (this) {
    ConnectionState.Unpaired -> "Not paired"
    is ConnectionState.Pairing -> "Pairing with $macName…"
    is ConnectionState.Searching -> "Looking for $macName…"
    is ConnectionState.Connected -> "Connected to $macName"
    is ConnectionState.Failed -> message
}

/** The line under [describe] on the main screen, or null when the title says it all. */
fun ConnectionState.hint(): String? = when (this) {
    ConnectionState.Unpaired -> "On your Mac, choose Pair New Phone… and scan its code"
    is ConnectionState.Pairing -> null
    is ConnectionState.Searching -> "Keep DroidDrop running on your Mac, on the same network"
    is ConnectionState.Connected -> "Files and clipboard sync over Wi-Fi"
    is ConnectionState.Failed -> null
}

/** The state to show when the service starts to reconnect: a fresh process says Unpaired until told otherwise. */
fun ConnectionState.onServiceStart(savedMacName: String?): ConnectionState =
    if (this == ConnectionState.Unpaired && savedMacName != null) ConnectionState.Searching(savedMacName) else this

/** Process-wide connection state, written by ConnectionService and observed by the UI. */
object ConnectionStatus {
    val state = MutableStateFlow<ConnectionState>(ConnectionState.Unpaired)
}
