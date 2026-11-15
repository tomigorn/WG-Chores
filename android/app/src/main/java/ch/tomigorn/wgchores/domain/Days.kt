package ch.tomigorn.wgchores.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Calendar dates in the household's time zone (= the server's TZ), as ISO strings like the API. */
object Days {
    fun zone(tz: String): ZoneId = runCatching { ZoneId.of(tz) }.getOrElse { ZoneId.systemDefault() }

    fun today(tz: String, now: Instant = Instant.now()): LocalDate = now.atZone(zone(tz)).toLocalDate()

    fun between(from: String, to: LocalDate): Int = ChronoUnit.DAYS.between(LocalDate.parse(from), to).toInt()

    fun minus(today: LocalDate, n: Int): String = today.minusDays(n.toLong()).toString()
}

sealed interface DayWord {
    data object Today : DayWord
    data object Yesterday : DayWord
    data class Date(val text: String) : DayWord
}

object Words {
    private fun german(locale: Locale) = locale.language == "de"

    /** "3 Nov" / "3. Nov.", with the year when it is not this year (same as the web app's en-GB / de-CH). */
    fun date(iso: String, today: LocalDate, locale: Locale): String {
        val d = LocalDate.parse(iso)
        val withYear = d.year != today.year
        val pattern = if (german(locale)) (if (withYear) "d. MMM yyyy" else "d. MMM") else if (withYear) "d MMM yyyy" else "d MMM"
        val fmtLocale = if (german(locale)) Locale.forLanguageTag("de-CH") else Locale.UK
        return DateTimeFormatter.ofPattern(pattern, fmtLocale).format(d)
    }

    fun dayWord(doneOn: String, today: LocalDate, locale: Locale): DayWord = when (Days.between(doneOn, today)) {
        0 -> DayWord.Today
        1 -> DayWord.Yesterday
        else -> DayWord.Date(date(doneOn, today, locale))
    }

    fun initials(name: String): String =
        name.trim().split(Regex("\\s+")).take(2).mapNotNull { w ->
            w.takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))).uppercase() }
        }.joinToString("").ifEmpty { "?" }
}
