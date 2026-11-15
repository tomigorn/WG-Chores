package ch.tomigorn.wgchores.notify

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Notification texts, the same wording as the server's web push (server/src/notify/texts.rs). */
object NotifyTexts {
    const val MAX_BODY_BYTES = 3000
    private const val MORE = ", …"

    /** "en" or "de": the app language, else the phone's. */
    fun lang(app: String, system: Locale): String = when (app) {
        "de", "en" -> app
        else -> if (system.language == "de") "de" else "en"
    }

    /** Android 13+ knows the per-app language itself; older versions only in an activity, so the tag stored by Settings counts there. */
    fun resolve(sdk: Int, stored: String?, current: String, system: Locale): String =
        lang(if (sdk >= 33 || stored == null) current else stored, system)

    private fun german(lang: String) = lang == "de"

    /** Title and body for the due chores of one household, [names] in the server's order. */
    fun due(lang: String, household: String, names: List<String>): Pair<String, String> {
        if (names.size == 1) return (if (german(lang)) "${names[0]} ist fällig" else "${names[0]} is due") to household
        val title = if (german(lang)) "${names.size} Aufgaben sind fällig" else "${names.size} chores are due"
        return title to join(names)
    }

    /** "Since 14:00: Sam ✓ Wipe stove, …"; [items] are (who, chore). */
    fun done(lang: String, since: String?, items: List<Pair<String, String>>): String {
        val list = join(items.map { (who, chore) -> "$who ✓ $chore" })
        return if (since == null) list else (if (german(lang)) "Seit $since: " else "Since $since: ") + list
    }

    /** The previous check as shown after "Since": the time today, else weekday and time within a week, else the date. */
    fun since(lang: String, last: Instant?, now: Instant, zone: ZoneId): String? {
        if (last == null) return null
        val locale = if (german(lang)) Locale.GERMAN else Locale.ENGLISH
        val then = last.atZone(zone)
        val pattern = when {
            then.toLocalDate() == now.atZone(zone).toLocalDate() -> "HH:mm"
            Duration.between(last, now) < Duration.ofDays(6) -> "EEE HH:mm"
            german(lang) -> "d. MMM"
            else -> "d MMM"
        }
        return DateTimeFormatter.ofPattern(pattern, locale).format(then)
    }

    /** Joined with ", " and cut at [MAX_BODY_BYTES] (UTF-8), ending in ", …" when cut. */
    private fun join(parts: List<String>): String {
        val out = StringBuilder()
        var used = 0
        for ((i, p) in parts.withIndex()) {
            val sep = if (i == 0) "" else ", "
            val reserve = if (i + 1 < parts.size) MORE.toByteArray().size else 0
            val len = p.toByteArray().size
            if (used + sep.length + len + reserve > MAX_BODY_BYTES) {
                out.append(if (out.isEmpty()) "…" else MORE)
                break
            }
            out.append(sep).append(p)
            used += sep.length + len
        }
        return out.toString()
    }
}
