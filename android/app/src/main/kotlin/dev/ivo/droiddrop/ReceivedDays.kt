package dev.ivo.droiddrop

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The View all screen's days: received files split by the local day they arrived. Pure, so it can be unit tested. */
object ReceivedDays {
    // English whatever the device locale, like ReceivedFileText (R1).
    private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)
    private val weekdayDayMonthYear = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.ENGLISH)
    private val shortWeekday = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val month = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
    private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    /**
     * One day's files. [heading] is "Today", "Yesterday", "Thursday, 12 Nov" or, for an earlier year,
     * "Tuesday, 30 Dec 2025"; the Timeline's date rail shows [weekday], [dayOfMonth] and [month] ("Dec 2025" then).
     */
    data class Day<T>(
        val heading: String,
        val weekday: String,
        val dayOfMonth: String,
        val month: String,
        val isToday: Boolean,
        val isRecent: Boolean,
        val items: List<T>,
    )

    /** [items] newest first, as ReceivedFiles lists them; each day keeps that order. */
    fun <T> group(items: List<T>, nowMillis: Long, zone: ZoneId, addedMillis: (T) -> Long): List<Day<T>> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return items
            .groupBy { Instant.ofEpochMilli(addedMillis(it)).atZone(zone).toLocalDate() }
            .map { (date, dayItems) -> day(date, today, dayItems) }
    }

    private fun <T> day(date: LocalDate, today: LocalDate, items: List<T>): Day<T> {
        val thisYear = date.year == today.year
        val heading = when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            // A date later than today, from clock skew, is a plain date (R2).
            else -> (if (thisYear) weekdayDayMonth else weekdayDayMonthYear).format(date)
        }
        return Day(
            heading = heading,
            weekday = shortWeekday.format(date),
            dayOfMonth = date.dayOfMonth.toString(),
            month = (if (thisYear) month else monthYear).format(date),
            isToday = date == today,
            isRecent = date == today || date == today.minusDays(1),
            items = items,
        )
    }

    /** "Today · 4 files · 55 MB"; older days leave the name to the date rail: "3 files · 86 MB". */
    fun summary(day: Day<*>, totalSize: String): String {
        val counted = "${ReceivedFileText.count(day.items.size)} · $totalSize"
        return if (day.isRecent) "${day.heading} · $counted" else counted
    }
}
