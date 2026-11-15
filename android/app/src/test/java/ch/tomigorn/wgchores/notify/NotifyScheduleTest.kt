package ch.tomigorn.wgchores.notify

import ch.tomigorn.wgchores.domain.Days
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifyScheduleTest {
    private val times = listOf("14:00", "19:00")
    private val zurich = "Europe/Zurich"
    private fun at(s: String) = Instant.parse(s)

    @Test fun laterTodayIsTheNextTime() =
        assertEquals(at("2026-11-15T13:00:00Z"), NotifySchedule.next(at("2026-11-15T09:00:00Z"), times, zurich))

    @Test fun betweenTheTimesTheEveningOne() =
        assertEquals(at("2026-11-15T18:00:00Z"), NotifySchedule.next(at("2026-11-15T13:30:00Z"), times, zurich))

    @Test fun afterTheLastTimeItIsTomorrowsFirst() =
        assertEquals(at("2026-11-16T13:00:00Z"), NotifySchedule.next(at("2026-11-15T18:30:00Z"), times, zurich))

    @Test fun exactlyAtATimeTakesTheNextOne() =
        assertEquals(at("2026-11-15T18:00:00Z"), NotifySchedule.next(at("2026-11-15T13:00:00Z"), times, zurich))

    @Test fun noValidTimesNoSchedule() {
        assertNull(NotifySchedule.next(at("2026-11-15T09:00:00Z"), emptyList(), zurich))
        assertNull(NotifySchedule.next(at("2026-11-15T09:00:00Z"), listOf("x", "25:00", "14"), zurich))
    }

    @Test fun unsortedAndRepeatedTimes() =
        assertEquals(at("2026-11-15T13:00:00Z"), NotifySchedule.next(at("2026-11-15T09:00:00Z"), listOf("19:00", "14:00", "14:00"), zurich))

    @Test fun springForwardKeepsTheLocalTime() {
        assertEquals(at("2026-03-29T12:00:00Z"), NotifySchedule.next(at("2026-03-28T18:30:00Z"), times, zurich))
        assertEquals(at("2026-03-28T13:00:00Z"), NotifySchedule.next(at("2026-03-28T09:00:00Z"), times, zurich))
    }

    @Test fun fallBackKeepsTheLocalTime() {
        assertEquals(at("2026-10-25T13:00:00Z"), NotifySchedule.next(at("2026-10-24T17:30:00Z"), times, zurich))
        assertEquals(at("2026-10-24T12:00:00Z"), NotifySchedule.next(at("2026-10-24T09:00:00Z"), times, zurich))
    }

    @Test fun aTimeInsideTheSpringGapRunsJustAfterIt() =
        assertEquals(at("2026-03-29T01:30:00Z"), NotifySchedule.next(at("2026-03-28T23:00:00Z"), listOf("02:30"), zurich))

    @Test fun aTimeInsideTheAutumnOverlapRunsOnce() {
        val first = NotifySchedule.next(at("2026-10-24T23:00:00Z"), listOf("02:30"), zurich)
        assertEquals(at("2026-10-25T00:30:00Z"), first)
        assertEquals(at("2026-10-26T01:30:00Z"), NotifySchedule.next(first!!, listOf("02:30"), zurich))
    }

    @Test fun thePhonesZoneDoesNotMatter() {
        val saved = TimeZone.getDefault()
        try {
            for (device in listOf("America/New_York", "Asia/Tokyo", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(device))
                assertEquals(device, at("2026-11-15T13:00:00Z"), NotifySchedule.next(at("2026-11-15T09:00:00Z"), times, zurich))
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun otherServerZones() {
        assertEquals(at("2026-11-15T19:00:00Z"), NotifySchedule.next(at("2026-11-15T09:00:00Z"), times, "America/New_York"))
        assertEquals(at("2026-11-15T14:00:00Z"), NotifySchedule.next(at("2026-11-15T09:00:00Z"), times, "UTC"))
    }

    @Test fun anUnknownZoneFallsBackToThePhonesZoneLikeTheDueDays() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(at("2026-11-15T10:00:00Z"), NotifySchedule.next(at("2026-11-15T09:00:00Z"), times, "Mars/Olympus"))
            assertEquals(Days.zone("Mars/Olympus"), NotifySchedule.zone("Mars/Olympus"))
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
