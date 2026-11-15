package ch.tomigorn.wgchores.ui.chore

import ch.tomigorn.wgchores.api.ApiClient
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.data.CachedChore
import ch.tomigorn.wgchores.data.outbox.Done
import ch.tomigorn.wgchores.data.outbox.Outbox
import ch.tomigorn.wgchores.testing.FakeServer
import ch.tomigorn.wgchores.testing.MemoryOutboxStore
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoreControllerTest {
    private val fake = FakeServer().start()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bearer = fake.token()
    private val api = ApiClient(fake.url, bearer = { bearer })
    private val outbox = Outbox(MemoryOutboxStore(), Done.sendWith(api), io)
    private val account = Account(fake.url, fake.me, fake.tz, api, outbox, Done(outbox, api))
    private val cached = mutableMapOf<Long, CachedChore>()
    private val cache = object : ChoreCache {
        override suspend fun save(a: Account, c: CachedChore) {
            cached[c.chore.id] = c
        }

        override suspend fun load(a: Account, id: Long) = cached[id]
    }

    @After fun stop() {
        scope.cancel()
        io.cancel()
        main.close()
        fake.close()
    }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(main) { block() } }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            Thread.sleep(10)
        }
    }

    private fun loaded(id: Long = 2): ChoreController = ChoreController(account, id, cache, scope).also { c -> onMain { c.load() } }

    @Test fun loadShowsTheChoreAndTheFirstPageNewestFirst() {
        fake.seedHistory(2, 30)
        val c = loaded()
        val s = c.state.value
        assertEquals(Phase.Ready, s.phase)
        assertEquals("Clean toilet", s.chore?.name)
        assertEquals(20, s.entries.size)
        assertEquals(s.entries.map { it.doneOn }.sortedDescending(), s.entries.map { it.doneOn })
        assertTrue(s.next != null)
        assertEquals(7, s.chore?.usualInterval)
    }

    @Test fun moreLoadsTheOlderEntriesUntilTheEnd() {
        fake.seedHistory(2, 30)
        val c = loaded()
        onMain { c.more() }
        waitUntil("second page") { c.state.value.entries.size == 31 }
        assertNull(c.state.value.next)
        assertEquals(31, c.state.value.entries.map { it.id }.toSet().size)
    }

    @Test fun doneTodayIsStoredAndShownFirst() {
        val c = loaded()
        onMain { c.tap() }
        waitUntil("sent") { fake.created().size == 1 }
        waitUntil("shown") { c.state.value.entries.first().doneOn == fake.today.toString() && !c.state.value.entries.first().waiting }
        assertEquals(0, c.state.value.chore?.daysSince)
    }

    @Test fun anEntryIsChangedOnTheServer() {
        fake.seedHistory(2, 3)
        val c = loaded()
        val e = c.state.value.entries.first { it.id == "hist-2-1" }
        onMain {
            c.openEntry(e)
            c.saveSheet(fake.today.minusDays(40).toString(), "new note")
        }
        waitUntil("patched") { fake.completions["hist-2-1"]?.note == "new note" }
        assertEquals(fake.today.minusDays(40).toString(), fake.completions["hist-2-1"]?.doneOn)
    }

    @Test fun anEntryIsDeletedAfterTheConfirmation() {
        fake.seedHistory(2, 3)
        val c = loaded()
        val e = c.state.value.entries.first { it.id == "hist-2-2" }
        onMain {
            c.openEntry(e)
            c.askDelete()
        }
        assertEquals(e, c.state.value.confirmDelete)
        assertNull(c.state.value.sheet)
        onMain { c.delete() }
        waitUntil("deleted") { fake.completions["hist-2-2"] == null }
        waitUntil("gone from the page") { c.state.value.entries.none { it.id == "hist-2-2" } }
    }

    @Test fun offlineShowsTheCachedPage() {
        loaded()
        fake.down = true
        val c = loaded()
        assertEquals(Phase.Ready, c.state.value.phase)
        assertTrue(c.state.value.offline)
        assertEquals("Clean toilet", c.state.value.chore?.name)
    }

    @Test fun anUnknownChoreIsGone() = assertEquals(Phase.Gone, loaded(99).state.value.phase)
}
