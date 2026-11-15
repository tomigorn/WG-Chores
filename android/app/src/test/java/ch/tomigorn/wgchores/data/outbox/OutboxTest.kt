package ch.tomigorn.wgchores.data.outbox

import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.testing.MemoryOutboxStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryOutboxStore()
    private val sent = CopyOnWriteArrayList<String>()
    private var now = 1_000_000_000_000L

    /** What the server answers next for a uuid; default success. */
    private val answers = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private var gate: CompletableDeferred<Unit>? = null

    private val outbox = Outbox(store, send = { i ->
        gate?.await()
        sent += "${i.kind}:${i.uuid}"
        answers[i.uuid]?.invoke()
    }, scope = scope, now = { now })

    @After fun stop() = scope.cancel()

    private fun mark(uuid: String, choreId: Long = 1) = OutboxItem(uuid, choreId, "2026-11-14", null)

    @Test fun addThenFlushSendsOnceAndEmpties() = runBlocking {
        outbox.add(mark("a"))
        assertEquals(1, outbox.count.value)
        val r = outbox.flush()
        assertEquals(1, r.sent)
        assertEquals(listOf("Mark:a"), sent)
        assertTrue(outbox.items().isEmpty())
        assertEquals(0, outbox.count.value)
        outbox.flush()
        assertEquals(1, sent.size)
    }

    @Test fun offlineKeepsTheItemAndStops() = runBlocking {
        answers["a"] = { throw OfflineException() }
        outbox.add(mark("a"))
        outbox.add(mark("b"))
        val r = outbox.flush()
        assertEquals(0, r.sent)
        assertEquals(listOf("Mark:a"), sent)
        assertEquals(listOf("a", "b"), outbox.items().map { it.uuid })
        assertTrue(outbox.items().first().tried)
    }

    @Test fun replayAfterARetryIsSuccess() = runBlocking {
        answers["a"] = { throw OfflineException() }
        outbox.add(mark("a"))
        outbox.flush()
        answers.remove("a")
        assertEquals(1, outbox.flush().sent)
        assertEquals(listOf("Mark:a", "Mark:a"), sent)
        assertTrue(outbox.items().isEmpty())
    }

    @Test fun rejectedItemsAreDroppedServerErrorsKept() = runBlocking {
        answers["bad"] = { throw ApiException(400, "invalid_date") }
        answers["gone"] = { throw ApiException(404, "not_found") }
        answers["busy"] = { throw ApiException(503, "http_503") }
        answers["slow"] = { throw ApiException(429, "too_many_requests") }
        answers["auth"] = { throw ApiException(401, "unauthenticated") }
        listOf("bad", "gone", "busy", "slow", "ok").forEach { outbox.add(mark(it)) }
        val r = outbox.flush()
        assertEquals(listOf("bad", "gone"), r.dropped.map { it.uuid })
        assertEquals(1, r.sent)
        assertEquals(listOf("busy", "slow"), outbox.items().map { it.uuid })
    }

    @Test fun unauthenticatedStopsTheFlush() = runBlocking {
        answers["a"] = { throw ApiException(401, "unauthenticated") }
        outbox.add(mark("a"))
        outbox.add(mark("b"))
        outbox.flush()
        assertEquals(listOf("Mark:a"), sent)
        assertEquals(2, outbox.items().size)
    }

    @Test fun itemsOlderThan30DaysAreDropped() = runBlocking {
        outbox.add(mark("old"))
        now += Outbox.MAX_AGE_MS - 1
        outbox.add(mark("new"))
        now += 1
        val r = outbox.flush()
        assertEquals(listOf("old"), r.dropped.map { it.uuid })
        assertEquals(listOf("Mark:new"), sent)
    }

    @Test fun flushResultsReachListenersWhoeverFlushes() = runBlocking {
        val heard = async { withTimeout(5_000) { outbox.flushed.first() } }
        kotlinx.coroutines.yield()
        Thread.sleep(50)
        outbox.add(mark("a"))
        outbox.flush()
        assertEquals(1, heard.await().sent)
    }

    @Test fun concurrentFlushesShareOneRun() = runBlocking {
        gate = CompletableDeferred()
        outbox.add(mark("a"))
        val one = async(Dispatchers.Default) { outbox.flush() }
        val two = async(Dispatchers.Default) { outbox.flush() }
        Thread.sleep(100)
        gate!!.complete(Unit)
        one.await()
        two.await()
        assertEquals(listOf("Mark:a"), sent)
    }

    @Test fun cancelRemovesAnUntriedMark() = runBlocking {
        outbox.add(mark("a"))
        assertEquals(Outbox.Cancel.Removed, outbox.cancel("a", 1))
        assertTrue(outbox.items().isEmpty())
        outbox.flush()
        assertTrue(sent.isEmpty())
    }

    @Test fun cancelTurnsATriedMarkIntoADelete() = runBlocking {
        answers["a"] = { throw OfflineException() }
        outbox.add(mark("a"))
        outbox.flush()
        assertEquals(Outbox.Cancel.Queued, outbox.cancel("a", 1))
        val i = outbox.items().single()
        assertEquals(OutboxItem.Kind.Delete, i.kind)
        answers.remove("a")
        outbox.flush()
        assertEquals(listOf("Mark:a", "Delete:a"), sent)
        assertTrue(outbox.items().isEmpty())
    }

    @Test fun cancelOfASentMarkQueuesADelete() = runBlocking {
        outbox.add(mark("a"))
        outbox.flush()
        assertEquals(Outbox.Cancel.Queued, outbox.cancel("a", 1))
        assertEquals(OutboxItem.Kind.Delete, outbox.items().single().kind)
    }

    @Test fun itemBeingSentIsInFlight() = runBlocking {
        gate = CompletableDeferred()
        outbox.add(mark("a"))
        val f = async(Dispatchers.Default) { outbox.flush() }
        while (!outbox.items().single().tried) Thread.sleep(5)
        assertEquals(Outbox.Cancel.InFlight, outbox.cancel("a", 1))
        assertFalse(outbox.update("a", "2026-11-13", "x"))
        gate!!.complete(Unit)
        f.await()
        assertEquals(Outbox.Cancel.Queued, outbox.cancel("a", 1))
    }

    @Test fun updateChangesAWaitingMark() = runBlocking {
        outbox.add(mark("a"))
        assertTrue(outbox.update("a", "2026-11-12", "note"))
        assertFalse(outbox.update("zzz", "2026-11-12", null))
        val i = outbox.items().single()
        assertEquals("2026-11-12", i.doneOn)
        assertEquals("note", i.note)
    }

    @Test fun markIsFlaggedTriedBeforeTheRequestGoesOut() = runBlocking {
        var seenTried = false
        val o = Outbox(store, send = { seenTried = store.all().single().tried }, scope = scope, now = { now })
        o.add(mark("a"))
        o.flush()
        assertTrue(seenTried)
    }
}
