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

/** Process-wide connection state, written by ConnectionService and observed by the UI. */
object ConnectionStatus {
    val state = MutableStateFlow<ConnectionState>(ConnectionState.Unpaired)
}
