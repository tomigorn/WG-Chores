package ch.tomigorn.wgchores.notify

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyTextsTest {
    @Test fun dueTextsInBothLanguages() {
        assertEquals("Clean toilet is due" to "WG Zürich", NotifyTexts.due("en", "WG Zürich", listOf("Clean toilet")))
        assertEquals("Clean toilet ist fällig" to "WG Zürich", NotifyTexts.due("de", "WG Zürich", listOf("Clean toilet")))
        assertEquals("2 chores are due" to "Clean shower, Clean toilet", NotifyTexts.due("en", "WG Zürich", listOf("Clean shower", "Clean toilet")))
        assertEquals("2 Aufgaben sind fällig" to "Clean shower, Clean toilet", NotifyTexts.due("de", "WG Zürich", listOf("Clean shower", "Clean toilet")))
    }

    @Test fun longDueListsAreCut() {
        val names = (0..60).map { "$it" + "ü".repeat(78) }
        val (title, body) = NotifyTexts.due("de", "Home", names)
        assertEquals("61 Aufgaben sind fällig", title)
        assertTrue(body.toByteArray().size <= NotifyTexts.MAX_BODY_BYTES)
        assertTrue(body.startsWith(names[0]))
        assertTrue(body, body.endsWith(", …"))
    }

    @Test fun doneSummary() {
        val items = listOf("Sam" to "Wipe stove", "Lena Example" to "Clean fridge")
        assertEquals("Since 14:00: Sam ✓ Wipe stove, Lena Example ✓ Clean fridge", NotifyTexts.done("en", "14:00", items))
        assertEquals("Seit 14:00: Sam ✓ Wipe stove, Lena Example ✓ Clean fridge", NotifyTexts.done("de", "14:00", items))
        assertEquals("Sam ✓ Wipe stove", NotifyTexts.done("en", null, items.take(1)))
    }

    @Test fun sinceIsTheTimeTodayElseTheWeekday() {
        val zone = ZoneId.of("Europe/Zurich")
        val now = Instant.parse("2026-11-15T18:05:00Z")
        assertEquals("14:00", NotifyTexts.since("en", Instant.parse("2026-11-15T13:00:00Z"), now, zone))
        assertEquals("Sat 19:00", NotifyTexts.since("en", Instant.parse("2026-11-14T18:00:00Z"), now, zone))
        assertEquals("Sa. 19:00", NotifyTexts.since("de", Instant.parse("2026-11-14T18:00:00Z"), now, zone))
        assertEquals("1 Nov", NotifyTexts.since("en", Instant.parse("2026-11-01T18:00:00Z"), now, zone))
        assertNull(NotifyTexts.since("en", null, now, zone))
    }

    @Test fun languageFollowsTheAppElseThePhone() {
        assertEquals("de", NotifyTexts.lang("de", Locale.ENGLISH))
        assertEquals("en", NotifyTexts.lang("en", Locale.GERMAN))
        assertEquals("de", NotifyTexts.lang("system", Locale.forLanguageTag("de-CH")))
        assertEquals("en", NotifyTexts.lang("system", Locale.FRENCH))
    }

    @Test fun storedLanguageCountsWhereTheSystemDoesNotKnowIt() {
        assertEquals("de", NotifyTexts.resolve(30, stored = "de", current = "system", system = Locale.ENGLISH))
        assertEquals("en", NotifyTexts.resolve(30, stored = "system", current = "system", system = Locale.ENGLISH))
        assertEquals("de", NotifyTexts.resolve(30, stored = null, current = "system", system = Locale.GERMAN))
        assertEquals("en", NotifyTexts.resolve(36, stored = "de", current = "en", system = Locale.GERMAN))
    }

    @Test fun mergesKeepOldItemsAndAddNewOnesOnce() {
        assertEquals(listOf("Clean toilet", "Clean shower"), NotifyMerge.due(listOf("Clean toilet"), listOf("Clean shower", "Clean toilet")))
        val a = DoneItem("Sam", "Wipe stove", "2026-11-15")
        val b = DoneItem("Lena Example", "Clean fridge", "2026-11-15")
        assertEquals(listOf(a, b), NotifyMerge.done(listOf(a), listOf(a, b)))
        assertEquals(listOf(a), DoneItem.decode(DoneItem.encode(listOf(a))))
    }
}
