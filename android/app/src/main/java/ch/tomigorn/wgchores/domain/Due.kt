package ch.tomigorn.wgchores.domain

import java.time.LocalDate

/** The due rule shared with the server and the web app (docs/test-vectors). */
object Due {
    const val FRESH = "fresh"
    const val SOON = "soon"
    const val CLOSE = "close"
    const val DUE = "due"
    const val OVERDUE = "overdue"
    const val LONG_OVERDUE = "long_overdue"
    const val NEVER = "never"

    val INTERVALS = linkedMapOf("3d" to 3, "1w" to 7, "2w" to 14, "1m" to 30, "3m" to 91, "6m" to 182, "1y" to 365)
    const val DEFAULT_INTERVAL = "1m"

    fun intervalDays(code: String?): Int = INTERVALS[code] ?: INTERVALS.getValue(DEFAULT_INTERVAL)

    fun level(daysSince: Int?, intervalDays: Int): String {
        val d = daysSince ?: return NEVER
        val iv = if (intervalDays > 0) intervalDays else 30
        return when {
            d * 2 < iv -> FRESH
            d * 5 < iv * 4 -> SOON
            d < iv -> CLOSE
            d * 2 < iv * 3 -> DUE
            d < iv * 2 -> OVERDUE
            else -> LONG_OVERDUE
        }
    }

    fun overdue(daysSince: Int, intervalDays: Int): Int = maxOf(0, daysSince - if (intervalDays > 0) intervalDays else 30)

    /** Median gap of the last up to 10 completions, null with fewer than 3. */
    fun usualInterval(doneDatesNewestFirst: List<String>): Int? {
        val days = doneDatesNewestFirst.take(10).map { LocalDate.parse(it).toEpochDay() }
        if (days.size < 3) return null
        val gaps = days.zipWithNext { a, b -> (a - b).toInt() }.sorted()
        val n = gaps.size
        return if (n % 2 == 1) gaps[(n - 1) / 2] else Math.floorDiv(gaps[n / 2 - 1] + gaps[n / 2] + 1, 2)
    }
}
