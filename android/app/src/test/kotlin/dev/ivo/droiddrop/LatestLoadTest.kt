package dev.ivo.droiddrop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LatestLoadTest {
    @Test
    fun keepsOnlyTheNewestLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val slowFirst = CompletableDeferred<String>()
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val latest = LatestLoad(scope, clock = { 0L }) {
            if (++calls == 1) {
                started.complete(Unit)
                slowFirst.await()
            } else {
                "second"
            }
        }
        try {
            latest.refresh()
            withTimeout(5_000) { started.await() }
            latest.refresh()
            withTimeout(5_000) { latest.snapshot.filterNotNull().first { it.value == "second" } }
            slowFirst.complete("first") // the stale load finishing late must not overwrite the newer result
            kotlinx.coroutines.delay(100)
            assertEquals("second", latest.snapshot.value?.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stampsEachRefreshWithTheClock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val times = ArrayDeque(listOf(100L, 200L))
        val latest = LatestLoad(scope, clock = { times.removeFirst() }) { "same" }
        try {
            latest.refresh()
            withTimeout(5_000) { latest.snapshot.filterNotNull().first { it.atMillis == 100L } }
            latest.refresh()
            // Same rows, new time: the screen must still redraw so "Today" labels move on.
            val second = withTimeout(5_000) { latest.snapshot.filterNotNull().first { it.atMillis == 200L } }
            assertEquals("same", second.value)
        } finally {
            scope.cancel()
        }
    }
}
