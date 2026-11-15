package ch.tomigorn.wgchores.data.outbox

import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A done mark waiting to be sent, or an Undo of one ([Kind.Delete]). */
data class OutboxItem(
    val uuid: String,
    val choreId: Long,
    val doneOn: String,
    val note: String?,
    val kind: Kind = Kind.Mark,
    /** A send was started, so the server may already have it. */
    val tried: Boolean = false,
    /** Queued at, ms since epoch. */
    val at: Long = 0,
) {
    enum class Kind { Mark, Delete }
}

/** Storage of one account's outbox, oldest first. */
interface OutboxStore {
    suspend fun all(): List<OutboxItem>
    suspend fun put(item: OutboxItem)
    suspend fun delete(uuid: String)
    suspend fun clear()
}

data class FlushResult(val sent: Int, val dropped: List<OutboxItem>)

/** Sends queued marks and Undos in order; the client uuid makes a resend after an unclear failure harmless. */
class Outbox(
    private val store: OutboxStore,
    private val send: suspend (OutboxItem) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Cancel { Removed, InFlight, Queued }

    private val mutex = Mutex()
    @Volatile private var inFlight: String? = null
    private var running: Deferred<FlushResult>? = null

    private val _count = MutableStateFlow(0)
    /** Waiting items, Undos included. */
    val count: StateFlow<Int> = _count
    private val _flushed = MutableSharedFlow<FlushResult>(extraBufferCapacity = 16)
    /** Every flush that sent or dropped something, whoever started it. */
    val flushed: SharedFlow<FlushResult> = _flushed

    suspend fun items(): List<OutboxItem> = store.all()

    /** Waiting done marks, without queued Undos. */
    suspend fun marks(): List<OutboxItem> = items().filter { it.kind == OutboxItem.Kind.Mark }

    suspend fun refreshCount() {
        _count.value = items().size
    }

    suspend fun add(i: OutboxItem) {
        mutex.withLock { store.put(if (i.at == 0L) i.copy(at = now()) else i) }
        refreshCount()
    }

    suspend fun update(uuid: String, doneOn: String, note: String?): Boolean {
        val ok = mutex.withLock {
            if (uuid == inFlight) return@withLock false
            val cur = items().find { it.uuid == uuid && it.kind == OutboxItem.Kind.Mark } ?: return@withLock false
            // the server may have it already and would answer a resend with the old date: such a mark is patched instead
            if (cur.tried) return@withLock false
            store.put(cur.copy(doneOn = doneOn, note = note))
            true
        }
        if (ok) refreshCount()
        return ok
    }

    /** Undo: an unsent mark is removed; one the server may have (or an absent one) becomes a queued delete. */
    suspend fun cancel(uuid: String, choreId: Long): Cancel {
        val r = mutex.withLock {
            if (uuid == inFlight) return@withLock Cancel.InFlight
            val cur = items().find { it.uuid == uuid }
            when {
                cur != null && cur.kind == OutboxItem.Kind.Mark && !cur.tried -> {
                    store.delete(uuid)
                    Cancel.Removed
                }
                cur?.kind == OutboxItem.Kind.Delete -> Cancel.Queued
                else -> {
                    store.put(OutboxItem(uuid, choreId, cur?.doneOn.orEmpty(), null, OutboxItem.Kind.Delete, at = now()))
                    Cancel.Queued
                }
            }
        }
        if (r != Cancel.InFlight) refreshCount()
        return r
    }

    suspend fun clear() {
        mutex.withLock { store.clear() }
        refreshCount()
    }

    /** Waits until no flush is running. */
    suspend fun idle() {
        val r = synchronized(this) { running }
        r?.join()
    }

    /** Starts a flush, or joins the one running. */
    suspend fun flush(): FlushResult = start().await()

    /** Fire-and-forget flush. */
    fun kick() {
        start()
    }

    private fun start(): Deferred<FlushResult> = synchronized(this) {
        running?.takeIf { it.isActive }?.let { return it }
        val d = scope.async(start = CoroutineStart.LAZY) { run() }
        running = d
        d.invokeOnCompletion { synchronized(this) { if (running === d) running = null } }
        d.start()
        d
    }

    private fun rejected(e: Throwable) = e is ApiException && e.status in 400..499 && e.status != 401 && e.status != 429

    private suspend fun run(): FlushResult {
        val dropped = mutex.withLock {
            val old = items().filter { now() - it.at >= MAX_AGE_MS }
            old.forEach { store.delete(it.uuid) }
            old
        }.toMutableList()
        var sent = 0
        val tried = mutableSetOf<String>()
        while (true) {
            val next = mutex.withLock {
                val i = items().firstOrNull { it.uuid !in tried }
                inFlight = i?.uuid
                if (i != null && i.kind == OutboxItem.Kind.Mark && !i.tried) store.put(i.copy(tried = true))
                i
            } ?: break
            tried += next.uuid
            try {
                send(next)
                sent++
                mutex.withLock { store.delete(next.uuid) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is OfflineException || (e is ApiException && e.status == 401)) break
                if (rejected(e)) {
                    dropped += next
                    mutex.withLock { store.delete(next.uuid) }
                }
            } finally {
                inFlight = null
            }
        }
        val result = FlushResult(sent, dropped)
        refreshCount()
        if (sent > 0 || dropped.isNotEmpty()) _flushed.emit(result)
        return result
    }

    companion object {
        const val MAX_AGE_MS = 30L * 86_400_000
    }
}
