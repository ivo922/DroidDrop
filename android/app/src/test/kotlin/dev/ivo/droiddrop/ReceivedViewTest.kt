package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReceivedViewTest {
    @Test
    fun storedChoiceRoundTrips() {
        ReceivedView.entries.forEach { assertEquals(it, ReceivedView.fromStored(it.stored)) }
    }

    @Test
    fun timelineUntilAChoiceIsSaved() {
        assertEquals(ReceivedView.TIMELINE, ReceivedView.fromStored(null))
        assertEquals(ReceivedView.TIMELINE, ReceivedView.fromStored("carousel"))
    }
}
