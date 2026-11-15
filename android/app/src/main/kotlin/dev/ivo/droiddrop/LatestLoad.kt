package dev.ivo.droiddrop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs [load] on each [refresh] and publishes only the newest call's result: a refresh cancels the one still running,
 * so a slow earlier load can never overwrite a newer list. Each result is stamped with [clock], so a refresh that
 * finds the same rows still moves "Today"/"Yesterday" labels on. Call [refresh] from one thread (the main one).
 */
class LatestLoad<T>(private val scope: CoroutineScope, private val clock: () -> Long, private val load: suspend () -> T) {
    data class Snapshot<T>(val value: T, val atMillis: Long)

    private val state = MutableStateFlow<Snapshot<T>?>(null)
    private var running: Job? = null

    val snapshot: StateFlow<Snapshot<T>?> = state.asStateFlow()

    fun refresh() {
        running?.cancel()
        running = scope.launch {
            val value = load()
            state.value = Snapshot(value, clock())
        }
    }
}
