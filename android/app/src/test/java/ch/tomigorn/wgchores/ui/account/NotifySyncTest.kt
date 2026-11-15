package ch.tomigorn.wgchores.ui.account

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifySyncTest {
    private var serverDone = true
    private var serverDue = false

    @Test fun aFailedChangeGoesBackToWhatTheServerConfirmed() = runBlocking {
        val sync = NotifySync { _, _ -> throw IllegalStateException("down") }
        sync.confirmed(done = true, due = false)
        val ok = sync.set(done = false, on = true)
        assertFalse(ok)
        assertEquals(NotifySync.State(done = true, due = false), sync.state.value)
    }

    @Test fun changesAreSentOneAfterTheOther() = runBlocking {
        val running = AtomicInteger(0)
        var most = 0
        val gate = CompletableDeferred<Unit>()
        val sync = NotifySync { done, on ->
            most = maxOf(most, running.incrementAndGet())
            gate.await()
            if (done) serverDone = on else serverDue = on
            running.decrementAndGet()
            NotifySync.State(serverDone, serverDue)
        }
        sync.confirmed(done = true, due = false)
        val a = async { sync.set(done = true, on = false) }
        val b = async { sync.set(done = false, on = true) }
        gate.complete(Unit)
        assertTrue(a.await() && b.await())
        assertEquals(1, most)
        assertEquals(NotifySync.State(done = false, due = true), sync.state.value)
    }

    @Test fun aFailureAfterAnEarlierSuccessKeepsTheEarlierOne() = runBlocking {
        var calls = 0
        val sync = NotifySync { done, on ->
            if (++calls == 2) throw IllegalStateException("down")
            if (done) serverDone = on else serverDue = on
            NotifySync.State(serverDone, serverDue)
        }
        sync.confirmed(done = true, due = false)
        assertTrue(sync.set(done = false, on = true))
        assertFalse(sync.set(done = true, on = false))
        assertEquals(NotifySync.State(done = true, due = true), sync.state.value)
    }
}
