package ui

import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Relative timestamp, e.g. "3 weeks ago", "2 hours ago", etc */
fun friendlyTimeAgo(dateTime: ZonedDateTime): String {
    // coerceAtLeast(0): clock skew shouldn't render as a negative duration.
    val seconds = Duration.between(dateTime, ZonedDateTime.now()).seconds.coerceAtLeast(0)
    val minute = 60L
    val hour = 60 * minute
    val day = 24 * hour
    val week = 7 * day
    val month = 30 * day
    val year = 365 * day
    return when {
        seconds < minute -> "just now"
        seconds < hour -> unitsAgo(seconds / minute, "min")
        seconds < day -> unitsAgo(seconds / hour, "hour")
        seconds < 2 * day -> "yesterday"
        seconds < week -> unitsAgo(seconds / day, "day")
        seconds < month -> unitsAgo(seconds / week, "week")
        seconds < year -> unitsAgo(seconds / month, "month")
        else -> unitsAgo(seconds / year, "year")
    }
}

private fun unitsAgo(count: Long, unit: String): String = "$count $unit${if (count == 1L) "" else "s"} ago"
