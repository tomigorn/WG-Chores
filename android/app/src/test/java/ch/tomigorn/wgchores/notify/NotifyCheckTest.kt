package ch.tomigorn.wgchores.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import ch.tomigorn.wgchores.api.ApiClient
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.data.outbox.Done
import ch.tomigorn.wgchores.data.outbox.Outbox
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.notify.NotifyCheck.Outcome
import ch.tomigorn.wgchores.testing.FakeServer
import ch.tomigorn.wgchores.testing.MemoryOutboxStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotifyCheckTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fake = FakeServer().start()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bearer = fake.token()
    private val api = ApiClient(fake.url, bearer = { bearer })
    private val outbox = Outbox(MemoryOutboxStore(), Done.sendWith(api), io)
    private val account = Account(fake.url, fake.me, fake.tz, api, outbox, Done(outbox, api))
    private val store by lazy { NotifyStore(PreferenceDataStoreFactory.create(scope = io) { File(tmp.root, "notify.preferences_pb") }) }

    data class Shown(val kind: String, val account: String, val household: Long, val items: List<String>, val since: String? = null, val lang: String = "en")

    private val shown = mutableListOf<Shown>()
    private var allowed = true
    private var failShow = false
    private var signedIn = true
    private var lang = "en"
    private var clock = Instant.parse("2026-11-15T13:00:30Z")
    private val events = mutableListOf<String>()

    private val show = object : NotifyShow {
        override fun allowed() = allowed
        override fun due(account: String, household: Ref, names: List<String>, lang: String) {
            if (failShow) throw IllegalStateException("no notification")
            shown += Shown("due", account, household.id, names, lang = lang)
        }
        override fun done(account: String, household: Ref, since: String?, items: List<DoneItem>, lang: String) {
            if (failShow) throw IllegalStateException("no notification")
            shown += Shown("done", account, household.id, items.map { "${it.by} ✓ ${it.chore}" }, since, lang)
        }
    }

    private val check = NotifyCheck(
        account = { if (signedIn) account else null },
        flush = { events += "flush:${polls()}" },
        store = store,
        show = show,
        lang = { lang },
        zone = { ZoneId.of("Europe/Zurich") },
        now = { clock },
    )

    private fun polls() = fake.requests.count { it.url.encodedPath == "/api/v1/notifications/poll" }
    private fun run() = runBlocking { check.run() }
    private fun cursor() = runBlocking { store.cursor(account.key) }
    private fun doneItems(hid: Long) = shown.filter { it.kind == "done" && it.household == hid }.flatMap { it.items }

    @After fun stop() {
        io.cancel()
        fake.close()
    }

    @Test fun firstCheckShowsTheDueChoresPerHouseholdAndStoresTheCursor() {
        fake.dueChores += listOf(1L, 2L)
        assertEquals(Outcome.Done, run())
        assertEquals(listOf(Shown("due", account.key, 1, listOf("Clean shower", "Clean toilet"))), shown)
        assertNotNull(cursor())
    }

    @Test fun germanTexts() {
        lang = "de"
        fake.dueChores += 2L
        run()
        assertEquals("de", shown.single().lang)
    }

    @Test fun theNextCheckAcknowledgesTheDueNotices() {
        fake.dueChores += 2L
        run()
        val first = cursor()
        run()
        assertEquals(1, shown.size)
        assertEquals(first, fake.requests.last { it.url.encodedPath == "/api/v1/notifications/poll" }.url.queryParameter("since"))
        assertTrue(fake.dueChores.isEmpty())
    }

    @Test fun aLostAnswerIsShownOnTheNextCheckOnce() {
        fake.dueChores += 2L
        fake.dropPollAnswers = 1
        assertEquals(Outcome.Retry, run())
        assertTrue(shown.isEmpty())
        assertNull(cursor())
        assertEquals(Outcome.Done, run())
        run()
        assertEquals(1, shown.count { it.items == listOf("Clean toilet") })
    }

    @Test fun notShownMeansNotAcknowledged() {
        fake.dueChores += 2L
        failShow = true
        assertEquals(Outcome.Retry, run())
        assertNull(cursor())
        failShow = false
        run()
        assertEquals(listOf(listOf("Clean toilet")), shown.map { it.items })
        run()
        assertEquals(1, shown.size)
    }

    @Test fun doneByOthersSinceTheLastCheckOnePerHousehold() {
        fake.chores += Chore(id = 20, householdId = 2, name = "Water plants", interval = "1w")
        run()
        fake.doneBy("Sam", 6)
        fake.doneBy("Lena Example", 20)
        fake.doneBy("Sam", 4)
        clock = Instant.parse("2026-11-15T18:00:20Z")
        run()
        assertEquals(Shown("done", account.key, 1, listOf("Sam ✓ Wipe stove", "Sam ✓ Clean fridge"), "14:00"), shown.single { it.kind == "done" && it.household == 1L })
        assertEquals(Shown("done", account.key, 2, listOf("Lena Example ✓ Water plants"), "14:00"), shown.single { it.kind == "done" && it.household == 2L })
    }

    @Test fun morePagesAreFetchedAndShownTogether() {
        run()
        repeat(5) { fake.doneBy("Sam", 6) }
        fake.pollPage = 2
        run()
        assertEquals(4, polls())
        assertEquals(5, doneItems(1).size)
        assertEquals(3, shown.count { it.kind == "done" })
    }

    @Test fun atMostFivePagesPerCheckTheRestNextTime() {
        assertEquals(Outcome.Done, run())
        repeat(7) { fake.doneBy("Sam", 6) }
        fake.pollPage = 1
        assertEquals(Outcome.Done, run())
        assertEquals(1 + NotifyCheck.MAX_PAGES, polls())
        assertEquals(5, doneItems(1).size)
        assertEquals(Outcome.Done, run())
        assertEquals(7, doneItems(1).size)
    }

    @Test fun aLostSecondPageIsFetchedAgainWithoutRepeatingTheFirst() {
        run()
        repeat(60) { fake.doneBy("Sam", 6) }
        fake.dropPolls = setOf(3)
        assertEquals(Outcome.Retry, run())
        assertEquals(50, doneItems(1).size)
        assertEquals(Outcome.Done, run())
        assertEquals(60, doneItems(1).size)
        assertEquals(1 + 2 + 1, polls())
    }

    @Test fun bothSwitchesOffNoPoll() {
        fake.notifyDone = false
        fake.notifyDue = false
        assertEquals(Outcome.Off, run())
        assertEquals(0, polls())
        assertFalse(runBlocking { store.enabled(account.key).first() })
    }

    @Test fun oneSwitchOnStillPolls() {
        fake.notifyDone = false
        fake.dueChores += 2L
        assertEquals(Outcome.Done, run())
        assertEquals(1, shown.size)
        assertTrue(runBlocking { store.enabled(account.key).first() })
    }

    @Test fun notificationsBlockedOnThePhoneNoPollSoNothingIsAcknowledged() {
        allowed = false
        fake.dueChores += 2L
        assertEquals(Outcome.Done, run())
        assertEquals(0, polls())
        allowed = true
        run()
        assertEquals(1, shown.size)
    }

    @Test fun waitingMarksAreSentFirst() {
        run()
        assertEquals(listOf("flush:0"), events)
    }

    @Test fun signedOutDoesNothing() {
        signedIn = false
        assertEquals(Outcome.SignedOut, run())
        assertEquals(0, polls())
    }

    @Test fun anInvalidCursorStartsOver() {
        runBlocking { store.saveCursor(account.key, "garbage") }
        fake.dueChores += 2L
        assertEquals(Outcome.Done, run())
        assertEquals(1, shown.size)
        assertTrue(cursor()!!.startsWith("c"))
    }

    @Test fun offlineRetries() {
        fake.down = true
        assertEquals(Outcome.Retry, run())
        assertNull(cursor())
    }

    @Test fun theLastCheckTimeIsRemembered() {
        run()
        assertEquals(clock.toEpochMilli(), runBlocking { store.lastCheck(account.key) })
    }
}
