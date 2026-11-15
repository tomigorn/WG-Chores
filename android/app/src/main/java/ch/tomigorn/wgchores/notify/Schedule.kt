package ch.tomigorn.wgchores.notify

import ch.tomigorn.wgchores.domain.Days
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When the background check runs: the server's notify times in the server's zone, whatever zone the phone is in. */
object NotifySchedule {
    private val hhmm = Regex("""(\d{2}):(\d{2})""")

    fun times(raw: List<String>): List<LocalTime> = raw.mapNotNull { t ->
        val m = hhmm.matchEntire(t.trim()) ?: return@mapNotNull null
        val (h, min) = m.destructured
        runCatching { LocalTime.of(h.toInt(), min.toInt()) }.getOrNull()
    }.distinct().sorted()

    fun zone(tz: String): ZoneId = Days.zone(tz)

    fun validZone(tz: String): Boolean = runCatching { ZoneId.of(tz) }.isSuccess

    /** The first notify time strictly after [after]; null without valid times. A time in a DST gap moves past the gap, one in an overlap counts once. */
    fun next(after: Instant, times: List<String>, tz: String): Instant? {
        val list = times(times)
        if (list.isEmpty()) return null
        val zone = zone(tz)
        val day = after.atZone(zone).toLocalDate()
        for (d in 0L..2L) {
            for (t in list) {
                val at = ZonedDateTime.of(day.plusDays(d), t, zone).toInstant()
                if (at.isAfter(after)) return at
            }
        }
        return null
    }
}
