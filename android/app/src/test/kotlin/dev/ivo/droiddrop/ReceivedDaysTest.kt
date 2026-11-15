package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReceivedDaysTest {
    private val sofia = ZoneId.of("Europe/Sofia")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(sofia).toInstant().toEpochMilli()
    private val now = at("2026-11-15T15:00")

    private fun group(vararg added: String) = ReceivedDays.group(added.map(::at), now, sofia) { it }

    @Test
    fun groupsNewestFirstByLocalDay() {
        val days = group("2026-11-15T14:02", "2026-11-15T08:45", "2026-11-14T23:59", "2026-11-12T10:05")
        assertEquals(listOf("Today", "Yesterday", "Thursday, 12 Nov"), days.map { it.heading })
        assertEquals(listOf(2, 1, 1), days.map { it.items.size })
        assertEquals(at("2026-11-15T14:02"), days[0].items[0])
    }

    @Test
    fun midnightStartsANewDay() {
        val days = group("2026-11-15T00:00", "2026-11-14T23:59")
        assertEquals(listOf("Today", "Yesterday"), days.map { it.heading })
    }

    @Test
    fun earlierYearHeadingsCarryTheYear() {
        val day = group("2025-12-30T23:58").single()
        assertEquals("Tuesday, 30 Dec 2025", day.heading)
        assertEquals("Tue", day.weekday)
        assertEquals("30", day.dayOfMonth)
        assertEquals("Dec 2025", day.month)
    }

    @Test
    fun railLabelsForThisYear() {
        val (today, older) = group("2026-11-15T10:12", "2026-11-09T21:14")
        assertTrue(today.isToday)
        assertEquals("Sun", today.weekday)
        assertEquals("15", today.dayOfMonth)
        assertEquals("Nov", today.month)
        assertFalse(older.isToday)
        assertEquals("Monday, 9 Nov", older.heading)
        assertEquals("9", older.dayOfMonth)
    }

    @Test
    fun aDayAfterTodayIsAPlainDate() {
        // Clock skew (R2): never "Tomorrow".
        assertEquals("Monday, 16 Nov", group("2026-11-16T09:00").single().heading)
    }

    @Test
    fun summaryNamesOnlyTodayAndYesterday() {
        val (today, yesterday, older) = group("2026-11-15T14:02", "2026-11-14T18:20", "2026-11-12T17:48")
        assertEquals("Today · 1 file · 55 MB", ReceivedDays.summary(today, "55 MB"))
        assertEquals("Yesterday · 1 file · 212 kB", ReceivedDays.summary(yesterday, "212 kB"))
        assertEquals("1 file · 86 MB", ReceivedDays.summary(older, "86 MB"))
    }
}
