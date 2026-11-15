package ch.tomigorn.wgchores.domain

import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private fun at(s: String) = Rules.expiresIn(s, now)

    @Test fun intervalChipsKeepTheWebOrder() {
        assertEquals(listOf("3d", "1w", "2w", "1m", "3m", "6m", "1y"), Intervals.ORDER)
    }

    @Test fun tappingThePickedChipUnpicksIt() {
        assertEquals("1w", Intervals.toggle(null, "1w"))
        assertNull(Intervals.toggle("1w", "1w"))
        assertEquals("1m", Intervals.toggle("1w", "1m"))
    }

    @Test fun expiryWords() {
        assertEquals(Expiry.Days(7), at("2026-11-21T12:00:00Z"))
        assertEquals(Expiry.Days(6), at("2026-11-21T11:59:59Z"))
        assertEquals(Expiry.Days(1), at("2026-11-15T12:00:00Z"))
        assertEquals(Expiry.Hours(23), at("2026-11-15T11:59:00Z"))
        assertEquals(Expiry.Hours(1), at("2026-11-14T13:00:00Z"))
        assertEquals(Expiry.Soon, at("2026-11-14T12:59:00Z"))
        assertEquals(Expiry.Expired, at("2026-11-14T12:00:00Z"))
        assertEquals(Expiry.Expired, at("2026-11-13T12:00:00Z"))
        assertEquals(Expiry.Expired, at("not a date"))
    }

    @Test fun expiryReadsOffsetsAndFractions() {
        assertEquals(Expiry.Days(2), at("2026-11-16T13:00:00.123+01:00"))
    }

    @Test fun passwordNeedsTwelveCodePointsAndAMatch() {
        assertEquals(PasswordProblem.Short, Rules.passwordProblem("elevenchars", "elevenchars"))
        assertEquals(PasswordProblem.Short, Rules.passwordProblem("😀😀😀😀😀😀😀😀😀😀😀", "😀😀😀😀😀😀😀😀😀😀😀"))
        assertNull(Rules.passwordProblem("😀😀😀😀😀😀😀😀😀😀😀😀", "😀😀😀😀😀😀😀😀😀😀😀😀"))
        assertEquals(PasswordProblem.Mismatch, Rules.passwordProblem("twelve chars", "twelve charz"))
        assertNull(Rules.passwordProblem("twelve chars", "twelve chars"))
    }

    @Test fun deleteConfirmation() {
        assertTrue(Rules.canConfirmDelete("sso", "Jane Doe", "Jane Doe"))
        assertTrue(Rules.canConfirmDelete("sso", "Jane Doe", "  Jane Doe "))
        assertFalse(Rules.canConfirmDelete("sso", "Jane Doe", "jane doe"))
        assertFalse(Rules.canConfirmDelete("sso", "Jane Doe", ""))
        assertTrue(Rules.canConfirmDelete("local", "Jane Doe", "x"))
        assertFalse(Rules.canConfirmDelete("local", "Jane Doe", ""))
    }

    @Test fun memberDirectoryDetection() = runBlocking {
        assertTrue(Rules.memberDirectory { })
        assertTrue(Rules.memberDirectory { throw ApiException(400, "query_too_short") })
        assertFalse(Rules.memberDirectory { throw ApiException(404, "not_found") })
        assertFalse(Rules.memberDirectory { throw ApiException(500, "http_500") })
        assertFalse(Rules.memberDirectory { throw OfflineException() })
    }

    @Test fun categorySuggestionsMatchCaseInsensitively() {
        val cats = listOf(Category("Bathroom", 3), Category("Kitchen", 2), Category("Balcony", 1))
        assertEquals(listOf("Bathroom", "Kitchen", "Balcony"), Rules.categorySuggestions("", cats))
        assertEquals(listOf("Bathroom", "Balcony"), Rules.categorySuggestions("ba", cats))
        assertEquals(listOf("Kitchen"), Rules.categorySuggestions(" KIT", cats))
        assertEquals(emptyList<String>(), Rules.categorySuggestions("kitchen", cats))
    }

    @Test fun renameAllOnlyForASharedCategoryIntoANewName() {
        val cats = listOf(Category("Bathroom", 3), Category("Kitchen", 1))
        assertEquals(3, Rules.renameAllCount("Bathroom", "Bath", cats))
        assertEquals(0, Rules.renameAllCount("Bathroom", "Kitchen", cats))
        assertEquals(0, Rules.renameAllCount("Bathroom", "Bathroom", cats))
        assertEquals(3, Rules.renameAllCount("Bathroom", "bathroom", cats))
        assertEquals(0, Rules.renameAllCount("Bathroom", "  ", cats))
        assertEquals(0, Rules.renameAllCount("Kitchen", "Cooking", cats))
        assertEquals(0, Rules.renameAllCount(null, "Cooking", cats))
    }

    @Test fun inviteLinks() {
        val s = "https://chores.example.com"
        assertEquals(InviteLink.Ok("abc_DEF-123"), InviteLink.parse("https://chores.example.com/invite/abc_DEF-123", s))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("  Join us: https://Chores.Example.com/invite/abc/ ", s))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("https://chores.example.com/invite/abc?x=1#y", s))
        assertEquals(InviteLink.OtherServer("other.example.com"), InviteLink.parse("https://other.example.com/invite/abc", s))
        assertEquals(InviteLink.Invalid, InviteLink.parse("https://chores.example.com/households/1", s))
        assertEquals(InviteLink.Invalid, InviteLink.parse("https://chores.example.com/invite/", s))
        assertEquals(InviteLink.Invalid, InviteLink.parse("https://chores.example.com/invite/a%2Fb", s))
        assertEquals(InviteLink.Invalid, InviteLink.parse("", s))
        assertEquals(InviteLink.OtherServer("chores.example.com"), InviteLink.parse("http://chores.example.com/invite/abc", s))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("http://localhost:8080/invite/abc", "http://localhost:8080"))
        assertEquals(InviteLink.OtherServer("localhost:9090"), InviteLink.parse("http://localhost:9090/invite/abc", "http://localhost:8080"))
    }

    @Test fun inviteLinksIgnoreDefaultPortsAndPunctuation() {
        val s = "https://chores.example.com"
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("https://chores.example.com:443/invite/abc", s))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("https://chores.example.com/invite/abc", "https://chores.example.com:443"))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("http://localhost:80/invite/abc", "http://localhost"))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("(see https://chores.example.com/invite/abc).", s))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("<https://chores.example.com/invite/abc>", s))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("Join: https://chores.example.com/invite/abc!?", s))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("https://chores.example.com/invite/abc,;", s))
        assertEquals(InviteLink.OtherServer("other.example.com"), InviteLink.parse("https://other.example.com:443/invite/x", s))
    }

    @Test fun inviteLinksTryEveryUrlInTheText() {
        val s = "https://chores.example.com"
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("https://other.example.com/invite/x or https://chores.example.com/invite/abc", s))
        assertEquals(InviteLink.Ok("abc"), InviteLink.parse("https://chores.example.com/help then https://chores.example.com/invite/abc", s))
        assertEquals(InviteLink.OtherServer("other.example.com"), InviteLink.parse("https://chores.example.com/help https://other.example.com/invite/x", s))
    }
}
