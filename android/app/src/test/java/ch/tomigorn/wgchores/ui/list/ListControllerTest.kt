package ch.tomigorn.wgchores.ui.list

import ch.tomigorn.wgchores.api.ApiClient
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.data.CachedList
import ch.tomigorn.wgchores.data.outbox.Done
import ch.tomigorn.wgchores.data.outbox.Outbox
import ch.tomigorn.wgchores.domain.Due
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListControllerTest {
    private val fake = FakeServer().start()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bearer = fake.token()
    private val api = ApiClient(fake.url, bearer = { bearer })
    private val store = MemoryOutboxStore()
    private val outbox = Outbox(store, Done.sendWith(api), io)
    private val account = Account(fake.url, fake.me, fake.tz, api, outbox, Done(outbox, api))
    private val cached = mutableMapOf<Long, CachedList>()
    private val cache = object : ListCache {
        override suspend fun save(a: Account, list: CachedList) {
            cached[list.household.id] = list
        }

        override suspend fun load(a: Account, hid: Long) = cached[hid]
    }
    private var clock = 1_000_000L
    private val opened = mutableListOf<Long>()

    private fun controller(hid: Long = 1, c: ListCache = cache) = ListController(account, hid, c, scope, onOpened = { opened += it }, now = { clock })

    private val broken = object : ListCache {
        override suspend fun save(a: Account, list: CachedList) = throw IllegalStateException("disk full")
        override suspend fun load(a: Account, hid: Long): CachedList? = throw IllegalStateException("disk gone")
    }

    private fun listLoads() = fake.requests.count { it.url.encodedPath == "/api/v1/households/1/chores" }

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

    private fun ListController.row(name: String) = state.value.chores.single { it.name == name }

    private fun loaded(): ListController = controller().also { c -> onMain { c.load() } }

    @Test fun loadShowsTheHouseholdAndRemembersIt() {
        val c = loaded()
        val s = c.state.value
        assertEquals("WG Zürich", s.household?.name)
        assertEquals(8, s.chores.size)
        assertTrue(s.loaded)
        assertEquals(listOf(1L), opened)
        assertEquals("WG Zürich", cached[1]?.household?.name)
    }

    @Test fun tapMarksDoneTodayAtOnceAndTheServerGetsIt() {
        val c = loaded()
        onMain { c.tap(4) }
        val row = c.row("Clean fridge")
        assertEquals(0, row.daysSince)
        assertEquals(Due.FRESH, row.dueLevel)
        assertTrue(c.state.value.snack is Snack.Done)
        waitUntil("completion on the server") { fake.created().size == 1 }
        assertEquals(fake.today.toString(), fake.created().values.single().doneOn)
    }

    @Test fun doubleTapMarksOnce() {
        val c = loaded()
        onMain {
            c.tap(4)
            c.tap(4)
        }
        waitUntil("first mark sent") { fake.created().size == 1 }
        clock += 500
        onMain { c.tap(4) }
        Thread.sleep(300)
        assertEquals(1, fake.created().size)
        clock += ListController.TAP_GUARD_MS
        onMain { c.tap(4) }
        waitUntil("second mark after the guard") { fake.created().size == 2 }
    }

    @Test fun undoAfterSendDeletesAndRestoresTheRow() {
        val c = loaded()
        val before = c.row("Clean toilet")
        onMain { c.tap(2) }
        waitUntil("sent") { fake.created().size == 1 && c.state.value.pending == 0 }
        onMain { c.undo() }
        assertEquals(before, c.row("Clean toilet"))
        assertNull(c.state.value.snack)
        waitUntil("deleted on the server") { fake.created().isEmpty() }
    }

    @Test fun offlineMarkWaitsAndIsSentExactlyOnceLater() {
        val c = loaded()
        fake.down = true
        onMain { c.tap(4) }
        waitUntil("queued") { c.state.value.pending == 1 }
        assertEquals(0, c.row("Clean fridge").daysSince)
        fake.down = false
        runBlocking { outbox.flush() }
        runBlocking { outbox.flush() }
        assertEquals(1, fake.created().size)
        waitUntil("count back to zero") { c.state.value.pending == 0 }
    }

    @Test fun undoOfAnOfflineMarkLeavesNothingBehind() {
        val c = loaded()
        fake.down = true
        onMain { c.tap(4) }
        waitUntil("queued") { c.state.value.pending == 1 }
        onMain { c.undo() }
        waitUntil("undo queued") { runBlocking { outbox.items() }.singleOrNull()?.kind == ch.tomigorn.wgchores.data.outbox.OutboxItem.Kind.Delete }
        fake.down = false
        runBlocking {
            outbox.idle()
            outbox.flush()
        }
        assertTrue(fake.created().isEmpty())
        assertTrue(runBlocking { outbox.items() }.isEmpty())
    }

    @Test fun offlineStartShowsTheCachedListWithPendingMarks() {
        loaded()
        fake.down = true
        val c = controller()
        onMain { c.load() }
        assertTrue(c.state.value.loaded)
        assertTrue(c.state.value.offline)
        assertEquals(8, c.state.value.chores.size)
        onMain { c.tap(4) }
        waitUntil("queued") { c.state.value.pending == 1 }
        val again = controller()
        onMain { again.load() }
        assertEquals(0, again.row("Clean fridge").daysSince)
    }

    @Test fun offlineStartWithoutACacheFails() {
        fake.down = true
        val c = controller()
        onMain { c.load() }
        assertTrue(c.state.value.failed)
        assertFalse(c.state.value.loaded)
    }

    @Test fun otherDateChangesTheMarkJustMade() {
        val c = loaded()
        onMain { c.tap(4) }
        waitUntil("sent") { fake.created().size == 1 && c.state.value.pending == 0 }
        onMain { c.openSheetFromSnack() }
        val sheet = c.state.value.sheet!!
        assertEquals(fake.today.toString(), sheet.doneOn)
        val yesterday = fake.today.minusDays(1).toString()
        onMain { c.saveSheet(yesterday, " used the new cleaner ") }
        waitUntil("patched") { fake.created().values.single().doneOn == yesterday }
        assertEquals("used the new cleaner", fake.created().values.single().note)
        assertEquals(1, c.row("Clean fridge").daysSince)
    }

    @Test fun longPressSheetMakesANewMark() {
        val c = loaded()
        onMain { c.openSheet(1) }
        assertNull(c.state.value.sheet!!.uuid)
        val twoDays = fake.today.minusDays(2).toString()
        onMain { c.saveSheet(twoDays, null) }
        waitUntil("sent") { fake.created().size == 1 }
        assertEquals(twoDays, fake.created().values.single().doneOn)
        assertEquals(2, c.row("Clean shower").daysSince)
    }

    @Test fun offlineEditSaysConnect() {
        val c = loaded()
        onMain { c.tap(4) }
        waitUntil("sent") { fake.created().size == 1 && c.state.value.pending == 0 }
        onMain { c.openSheetFromSnack() }
        fake.down = true
        onMain { c.saveSheet(fake.today.minusDays(1).toString(), null) }
        waitUntil("connect notice") { (c.state.value.snack as? Snack.Text)?.text == Notice.Connect }
        assertEquals(0, c.row("Clean fridge").daysSince)
    }

    @Test fun refusedMarkIsDroppedRevertedAndSaid() {
        val c = loaded()
        val before = c.row("Clean fridge")
        onMain { c.openSheet(4) }
        onMain { c.saveSheet(fake.today.plusDays(3).toString(), null) }
        waitUntil("dropped notice") { (c.state.value.snack as? Snack.Text)?.text == Notice.Dropped(1, "Clean fridge") }
        waitUntil("row back") { c.row("Clean fridge") == before }
        assertEquals(0, c.state.value.pending)
    }

    @Test fun aHouseholdThatIsGoneSendsBackToTheList() {
        val c = controller(hid = 99)
        onMain { c.load() }
        assertTrue(c.state.value.gone)
    }

    @Test fun cacheFailuresAreNoCrash() {
        val c = controller(c = broken)
        onMain { c.load() }
        assertTrue(c.state.value.loaded)
        fake.down = true
        val offline = controller(c = broken)
        onMain { offline.load() }
        assertTrue(offline.state.value.failed)
    }

    @Test fun anUndoThatCouldNotBeSavedSaysSo() {
        val c = loaded()
        runBlocking {
            outbox.add(ch.tomigorn.wgchores.data.outbox.OutboxItem("old-undo", 4, "", null, ch.tomigorn.wgchores.data.outbox.OutboxItem.Kind.Delete, at = 1))
            outbox.flush()
        }
        waitUntil("undo notice") { (c.state.value.snack as? Snack.Text)?.text == Notice.UndoDropped(1) }
    }

    @Test fun onlyTheShownListReloadsAfterAFlush() {
        val shown = loaded()
        val hidden = loaded()
        hidden.active = false
        val before = listLoads()
        runBlocking {
            outbox.add(ch.tomigorn.wgchores.data.outbox.OutboxItem("u-1", 4, fake.today.toString(), null))
            outbox.flush()
        }
        waitUntil("shown list reloaded") { listLoads() == before + 1 }
        Thread.sleep(300)
        assertEquals(before + 1, listLoads())
        assertTrue(shown.state.value.loaded)
    }
}
