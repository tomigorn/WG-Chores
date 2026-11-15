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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoneTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class FakeApi : CompletionApi {
        val calls = CopyOnWriteArrayList<String>()
        val server = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var offline = false
        @Volatile var fail: ApiException? = null
        @Volatile var gate: CompletableDeferred<Unit>? = null
        /** The server stores the completion but the answer never arrives. */
        @Volatile var loseAnswer = false
        val patches = CopyOnWriteArrayList<String>()

        private fun check() {
            if (offline) throw OfflineException()
            fail?.let { throw it }
        }

        override suspend fun complete(choreId: Long, uuid: String, doneOn: String, note: String?) {
            gate?.await()
            calls += "complete $uuid $doneOn"
            check()
            server.putIfAbsent(uuid, doneOn)
            if (loseAnswer) throw OfflineException()
        }

        override suspend fun deleteCompletion(uuid: String) {
            calls += "delete $uuid"
            check()
            if (server.remove(uuid) == null) throw ApiException(404, "not_found")
        }

        override suspend fun patchCompletion(uuid: String, doneOn: String, note: String?) {
            calls += "patch $uuid $doneOn"
            patches += uuid
            check()
            if (!server.containsKey(uuid)) throw ApiException(404, "not_found")
            server[uuid] = doneOn
        }
    }

    private val api = FakeApi()
    private val outbox = Outbox(MemoryOutboxStore(), Done.sendWith(api), scope)
    private var n = 0
    private val done = Done(outbox, api) { "uuid-${++n}" }

    @After fun stop() = scope.cancel()

    @Test fun markSendsTheCompletionWithTheClientUuid() = runBlocking {
        val r = done.mark(7, "2026-11-14", null)
        assertEquals(Done.Mark("uuid-1", queued = false, rejected = false), r)
        assertEquals(listOf("complete uuid-1 2026-11-14"), api.calls)
    }

    @Test fun markOfflineIsQueued() = runBlocking {
        api.offline = true
        assertTrue(done.mark(7, "2026-11-14", null).queued)
        assertEquals(1, outbox.count.value)
    }

    @Test fun rejectedMarkIsReported() = runBlocking {
        api.fail = ApiException(400, "invalid_date")
        val r = done.mark(7, "2030-01-01", null)
        assertTrue(r.rejected)
        assertFalse(r.queued)
    }

    @Test fun markTheServerAlreadyUndidIsSettledWithoutANotice() = runBlocking {
        api.fail = ApiException(410, "deleted")
        val r = done.mark(7, "2026-11-14", null)
        assertEquals(Done.Mark("uuid-1", queued = false, rejected = false), r)
        assertTrue(outbox.items().isEmpty())
        api.fail = null
        outbox.add(OutboxItem("late", 7, "2026-11-14", null))
        api.fail = ApiException(410, "deleted")
        val f = outbox.flush()
        assertTrue(f.dropped.isEmpty())
        assertEquals(1, f.sent)
    }

    @Test fun undoBeforeSendRemovesItFromTheOutbox() = runBlocking {
        api.offline = true
        val r = done.mark(7, "2026-11-14", null)
        // the offline attempt marked it tried; a mark never tried is removed without a request
        outbox.add(OutboxItem("fresh", 7, "2026-11-14", null))
        assertEquals(Done.Undo.Ok, done.undo("fresh", 7))
        assertEquals(listOf("uuid-1"), outbox.items().map { it.uuid })
        assertTrue(r.queued)
    }

    @Test fun undoAfterSendDeletesOnTheServer() = runBlocking {
        val r = done.mark(7, "2026-11-14", null)
        assertEquals(Done.Undo.Ok, done.undo(r.uuid, 7))
        assertTrue(api.server.isEmpty())
        assertEquals("delete uuid-1", api.calls.last())
    }

    @Test fun undoOfATriedMarkIsQueuedAndRetried() = runBlocking {
        api.offline = true
        val r = done.mark(7, "2026-11-14", null)
        assertEquals(Done.Undo.Queued, done.undo(r.uuid, 7))
        assertEquals(OutboxItem.Kind.Delete, outbox.items().single().kind)
        api.offline = false
        outbox.flush()
        assertTrue(outbox.items().isEmpty())
        assertEquals("delete uuid-1", api.calls.last())
    }

    @Test fun undoAfterASentMarkSurvivesAnOfflineMoment() = runBlocking {
        val r = done.mark(7, "2026-11-14", null)
        api.offline = true
        assertEquals(Done.Undo.Queued, done.undo(r.uuid, 7))
        api.offline = false
        outbox.flush()
        assertTrue(api.server.isEmpty())
    }

    @Test fun undoRefusedByTheServerIsAnError() = runBlocking {
        val r = done.mark(7, "2026-11-14", null)
        api.fail = ApiException(403, "forbidden")
        assertEquals(Done.Undo.Error, done.undo(r.uuid, 7))
    }

    @Test fun undoWaitsForASendInFlight() = runBlocking {
        api.gate = CompletableDeferred()
        val m = async(Dispatchers.Default) { done.mark(7, "2026-11-14", null) }
        while (outbox.items().firstOrNull()?.tried != true) Thread.sleep(5)
        val u = async(Dispatchers.Default) { done.undo("uuid-1", 7) }
        Thread.sleep(50)
        api.gate!!.complete(Unit)
        m.await()
        assertEquals(Done.Undo.Ok, u.await())
        assertTrue(api.server.isEmpty())
        assertTrue(outbox.items().isEmpty())
    }

    @Test fun changeOfAnUntriedMarkStaysLocal() = runBlocking {
        outbox.add(OutboxItem("fresh", 7, "2026-11-14", null))
        assertEquals(Done.Edit.Ok, done.change("fresh", "2026-11-12", "x"))
        assertEquals("2026-11-12", outbox.items().single().doneOn)
        assertTrue(api.patches.isEmpty())
    }

    @Test fun changeOfATriedMarkIsPatchedNotLostToTheReplay() = runBlocking {
        api.loseAnswer = true
        val r = done.mark(7, "2026-11-14", null)
        assertTrue(r.queued)
        api.loseAnswer = false
        assertEquals(Done.Edit.Ok, done.change(r.uuid, "2026-11-12", "x"))
        assertEquals(listOf(r.uuid), api.patches)
        outbox.flush()
        assertEquals("2026-11-12", api.server[r.uuid])
        assertTrue(outbox.items().isEmpty())
    }

    @Test fun changeOfATriedMarkOfflineSaysConnect() = runBlocking {
        api.offline = true
        val r = done.mark(7, "2026-11-14", null)
        assertEquals(Done.Edit.Offline, done.change(r.uuid, "2026-11-12", "x"))
        assertEquals("2026-11-14", outbox.items().single().doneOn)
    }

    @Test fun changeOfASentMarkPatches() = runBlocking {
        val r = done.mark(7, "2026-11-14", null)
        assertEquals(Done.Edit.Ok, done.change(r.uuid, "2026-11-12", "x"))
        assertEquals("2026-11-12", api.server[r.uuid])
    }

    @Test fun offlineEditSaysConnect() = runBlocking {
        val r = done.mark(7, "2026-11-14", null)
        api.offline = true
        assertEquals(Done.Edit.Offline, done.change(r.uuid, "2026-11-12", null))
        api.offline = false
        api.fail = ApiException(500, "http_500")
        assertEquals(Done.Edit.Error, done.change(r.uuid, "2026-11-12", null))
    }

    @Test fun uuidsAreV4() {
        val u = Done.newUuid()
        assertTrue(u, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(u))
    }
}
