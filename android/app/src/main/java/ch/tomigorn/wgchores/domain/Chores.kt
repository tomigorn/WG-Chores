package ch.tomigorn.wgchores.domain

import java.text.Collator
import java.time.LocalDate
import java.util.Locale

data class ChoreGroup(val category: String?, val chores: List<Chore>)

data class PendingDone(val uuid: String, val choreId: Long, val doneOn: String, val note: String?)

/** Locale-aware compare with numbers compared by value ("Room 2" before "Room 10"), like Intl.Collator numeric. */
class NaturalOrder(locale: Locale, strength: Int) : Comparator<String> {
    private val collator = Collator.getInstance(locale).apply { this.strength = strength }

    private fun chunks(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val digit = s[i] in '0'..'9'
            var j = i
            while (j < s.length && (s[j] in '0'..'9') == digit) j++
            out += s.substring(i, j)
            i = j
        }
        return out
    }

    override fun compare(a: String, b: String): Int {
        val x = chunks(a)
        val y = chunks(b)
        for (k in 0 until minOf(x.size, y.size)) {
            val p = x[k]
            val q = y[k]
            val r = if (p[0] in '0'..'9' && q[0] in '0'..'9') {
                val pt = p.trimStart('0')
                val qt = q.trimStart('0')
                if (pt.length != qt.length) pt.length - qt.length else pt.compareTo(qt)
            } else {
                collator.compare(p, q)
            }
            if (r != 0) return r
        }
        return x.size - y.size
    }
}

object Chores {
    /** Categories A–Z (no category last), chores A–Z inside; case- and accent-insensitive. */
    fun group(chores: List<Chore>, locale: Locale): List<ChoreGroup> {
        val base = NaturalOrder(locale, Collator.PRIMARY)
        val exact = NaturalOrder(locale, Collator.TERTIARY)
        val byKey = LinkedHashMap<String, Pair<String?, MutableList<Chore>>>()
        for (c in chores) {
            val name = c.category?.trim()?.takeIf { it.isNotEmpty() }
            val key = if (name == null) "" else "c" + name.lowercase(locale)
            val g = byKey[key]
            if (g == null) {
                byKey[key] = name to mutableListOf(c)
            } else {
                g.second += c
                if (name != null && g.first != null && exact.compare(name, g.first!!) < 0) byKey[key] = name to g.second
            }
        }
        return byKey.values
            .map { (cat, list) -> ChoreGroup(cat, list.sortedWith { a, b -> base.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }) }
            .sortedWith { a, b ->
                when {
                    a.category == null -> 1
                    b.category == null -> -1
                    else -> base.compare(a.category, b.category)
                }
            }
    }

    /** The chore as it looks after a completion on [doneOn]; an older date than the last one changes nothing. */
    fun applyCompletion(c: Chore, doneOn: String, by: By, today: LocalDate): Chore {
        if (c.last != null && c.last.doneOn > doneOn) return c
        val days = maxOf(0, Days.between(doneOn, today))
        val iv = Due.intervalDays(c.interval)
        return c.copy(last = LastDone(doneOn, by), daysSince = days, dueLevel = Due.level(days, iv), daysOverdue = Due.overdue(days, iv))
    }

    fun withPending(chores: List<Chore>, pending: List<PendingDone>, me: By, today: LocalDate): List<Chore> {
        if (pending.isEmpty()) return chores
        return chores.map { c -> pending.filter { it.choreId == c.id }.fold(c) { acc, p -> applyCompletion(acc, p.doneOn, me, today) } }
    }

    /** A cached list seen on a later day: days since and due levels move on. */
    fun rebase(chores: List<Chore>, today: LocalDate): List<Chore> =
        chores.map { c -> c.last?.let { applyCompletion(c.copy(last = null), it.doneOn, it.by, today) } ?: c }
}
