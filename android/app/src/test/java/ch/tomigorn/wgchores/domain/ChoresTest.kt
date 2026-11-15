package ch.tomigorn.wgchores.domain

import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChoresTest {
    private val anna = By(1, "Anna")
    private val sam = By(2, "Sam")
    private val de = Locale.GERMAN
    private val en = Locale.ENGLISH

    private fun chore(id: Long, name: String, category: String?, interval: String? = "1w") =
        Chore(id = id, householdId = 1, name = name, category = category, interval = interval)

    private fun d(s: String) = LocalDate.parse(s)

    @Test fun groupsAndSortsLocaleAware() {
        val list = listOf(
            chore(1, "wipe stove", "Küche"),
            chore(2, "Älpli watering", "balkon"),
            chore(3, "Zebra plant", "Balkon"),
            chore(4, "abwaschen", "Küche"),
            chore(5, "Clean toilet", "Bad"),
            chore(6, "Ölen", "Ärger"),
            chore(7, "loose", null),
        )
        val g = Chores.group(list, de)
        assertEquals(listOf("Ärger", "Bad", "balkon", "Küche", null), g.map { it.category })
        assertEquals(listOf("Älpli watering", "Zebra plant"), g[2].chores.map { it.name })
        assertEquals(listOf("abwaschen", "wipe stove"), g[3].chores.map { it.name })
        assertEquals(listOf(7L), g[4].chores.map { it.id })
    }

    @Test fun umlautsSortWithTheirBaseLetter() {
        val g = Chores.group(listOf(chore(1, "Zimmer", null), chore(2, "ölen", null), chore(3, "Ofen", null), chore(4, "apfel", null), chore(5, "Äpfel", null)), de)
        assertEquals(listOf("apfel", "Äpfel", "Ofen", "ölen", "Zimmer"), g.single().chores.map { it.name })
    }

    @Test fun numbersSortByValue() {
        val g = Chores.group(listOf(chore(1, "Room 10", null), chore(2, "Room 2", null), chore(3, "room 1", null)), en)
        assertEquals(listOf("room 1", "Room 2", "Room 10"), g.single().chores.map { it.name })
    }

    @Test fun categoryCasingIsStable() {
        val a = listOf(chore(1, "x", "Balkon"), chore(2, "y", "balkon"), chore(3, "z", "BALKON"))
        val names = listOf(a, a.reversed(), listOf(a[1], a[2], a[0])).map { Chores.group(it, de)[0].category }
        assertEquals(1, names.toSet().size)
        assertEquals(3, Chores.group(a, de)[0].chores.size)
    }

    @Test fun blankCategoryCountsAsNone() {
        assertEquals(listOf<String?>(null), Chores.group(listOf(chore(1, "a", "  "), chore(2, "b", null)), en).map { it.category })
    }

    @Test fun todayInTheServerTimeZone() {
        val t = Instant.parse("2026-11-14T23:30:00Z")
        assertEquals(d("2026-11-15"), Days.today("Europe/Zurich", t))
        assertEquals(d("2026-11-14"), Days.today("America/New_York", t))
        Days.today("No/Such_Zone", t)
        assertEquals("2026-02-28", Days.minus(d("2026-03-01"), 1))
    }

    @Test fun relativeDateWords() {
        val today = d("2026-11-14")
        assertEquals(DayWord.Today, Words.dayWord("2026-11-14", today, en))
        assertEquals(DayWord.Yesterday, Words.dayWord("2026-11-13", today, en))
        assertEquals(DayWord.Date("3 Nov"), Words.dayWord("2026-11-03", today, en))
        assertEquals(DayWord.Date("30 Dec 2025"), Words.dayWord("2025-12-30", today, en))
        assertEquals(DayWord.Date("3. Nov."), Words.dayWord("2026-11-03", today, de))
        assertEquals(DayWord.Date("30. Dez. 2025"), Words.dayWord("2025-12-30", today, de))
    }

    @Test fun initials() {
        assertEquals("JD", Words.initials("Jane Doe"))
        assertEquals("Ä", Words.initials(" äbi "))
        assertEquals("?", Words.initials(" "))
        assertEquals("AB", Words.initials("anna bea cora"))
    }

    @Test fun completionUpdatesTheRowOnlyWhenNewer() {
        val c = chore(1, "Clean toilet", "Bad").copy(daysSince = 26, dueLevel = Due.LONG_OVERDUE, daysOverdue = 19, last = LastDone("2026-10-19", sam))
        val today = d("2026-11-14")
        val done = Chores.applyCompletion(c, "2026-11-14", anna, today)
        assertEquals(LastDone("2026-11-14", anna), done.last)
        assertEquals(0, done.daysSince)
        assertEquals(Due.FRESH, done.dueLevel)
        assertEquals(0, done.daysOverdue)
        assertSame(c, Chores.applyCompletion(c, "2026-10-01", anna, today))
        val threeDays = Chores.applyCompletion(chore(2, "x", null, "3d"), "2026-11-11", anna, today)
        assertEquals(listOf(3, Due.DUE, 0), listOf(threeDays.daysSince, threeDays.dueLevel, threeDays.daysOverdue))
    }

    @Test fun pendingMarksShowOnTheList() {
        val list = listOf(chore(1, "a", null), chore(2, "b", null))
        val out = Chores.withPending(list, listOf(PendingDone("u", 2, "2026-11-13", null)), anna, d("2026-11-14"))
        assertSame(list[0], out[0])
        assertEquals(1, out[1].daysSince)
        assertEquals(anna, out[1].last?.by)
    }

    @Test fun cachedListDaysFollowToday() {
        val c = chore(1, "a", null).copy(daysSince = 2, dueLevel = Due.FRESH, last = LastDone("2026-11-10", anna))
        val r = Chores.rebase(listOf(c, chore(2, "never", null)), d("2026-11-19"))
        assertEquals(listOf(9, Due.DUE, 2), listOf(r[0].daysSince, r[0].dueLevel, r[0].daysOverdue))
        assertEquals(Due.NEVER, r[1].dueLevel)
    }

    private fun vectors(name: String): JsonArray {
        val text = javaClass.classLoader!!.getResource(name)!!.readText()
        return Json.parseToJsonElement(text).jsonArray
    }

    @Test fun dueLevelVectors() {
        val all = vectors("due-level.json")
        check(all.size > 10)
        for (v in all) {
            val o = v.jsonObject
            val days = o["days_since"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.int }
            val iv = o["interval"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals("$o", o["level"]!!.jsonPrimitive.content, Due.level(days, Due.intervalDays(iv)))
        }
    }

    @Test fun usualIntervalVectors() {
        val all = vectors("usual-interval.json")
        check(all.size > 5)
        for (v in all) {
            val o = v.jsonObject
            val dates = o["dates"]!!.jsonArray.map { it.jsonPrimitive.content }
            val usual = o["usual"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals("$dates", usual, Due.usualInterval(dates))
        }
    }

    @Test fun overdueFloorsAtZero() {
        assertEquals(0, Due.overdue(5, 7))
        assertEquals(3, Due.overdue(10, 7))
        assertEquals(10, Due.overdue(40, 0))
        assertEquals(30, Due.intervalDays(null))
        assertEquals(30, Due.intervalDays("biweekly"))
    }
}
