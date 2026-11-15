package ch.tomigorn.wgchores.ui.list

import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.data.CachedList
import ch.tomigorn.wgchores.data.outbox.Done
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Chores
import ch.tomigorn.wgchores.domain.Days
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.domain.PendingDone
import java.time.LocalDate
import ch.tomigorn.wgchores.data.outbox.OutboxItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface ListCache {
    suspend fun save(a: Account, list: CachedList)
    suspend fun load(a: Account, hid: Long): CachedList?
}

sealed interface Snack {
    /** "✓ <name> – done <when> · Undo · Other date"; [prev] is the row before the mark. */
    data class Done(val prev: Chore, val uuid: String, val doneOn: String, val note: String?, val otherDate: Boolean) : Snack
    data class Text(val text: Notice) : Snack
}

sealed interface Notice {
    data object Connect : Notice
    data object Failed : Notice
    data class Dropped(val count: Int, val name: String?) : Notice
    data class Waiting(val count: Int) : Notice
    data object Offline : Notice
    data class UndoDropped(val count: Int) : Notice
}

data class DoneSheet(val chore: Chore, val prev: Chore, val uuid: String?, val doneOn: String, val note: String?)

data class ListUi(
    val today: LocalDate,
    val household: Household? = null,
    val chores: List<Chore> = emptyList(),
    val loaded: Boolean = false,
    val offline: Boolean = false,
    val failed: Boolean = false,
    val refreshing: Boolean = false,
    val pending: Int = 0,
    val snack: Snack? = null,
    val snackSeq: Long = 0,
    val sheet: DoneSheet? = null,
    /** The household is gone or not ours any more: show the household list. */
    val gone: Boolean = false,
)

/** The chore list of one household: loading, offline cache, optimistic done marks with Undo and the date sheet. */
class ListController(
    private val account: Account,
    val householdId: Long,
    private val cache: ListCache,
    private val scope: CoroutineScope,
    private val onOpened: suspend (Long) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { Days.today(account.tz) },
) {
    private val outbox = account.outbox
    private val done: Done = account.done
    private val _state = MutableStateFlow(ListUi(today()))
    val state: StateFlow<ListUi> = _state

    /** Only the list on screen reloads after a flush. */
    @Volatile var active = true

    private var fetchSeq = 0
    private var mutations = 0
    private var opened = false
    private val sending = mutableSetOf<Long>()
    private val lastTap = mutableMapOf<Long, Long>()

    init {
        scope.launch { outbox.count.collect { n -> _state.update { it.copy(pending = n) } } }
        scope.launch {
            outbox.flushed.collect { r ->
                if (!active) return@collect
                val (marks, undos) = r.dropped.partition { it.kind == OutboxItem.Kind.Mark }
                if (marks.isNotEmpty()) {
                    val name = marks.singleOrNull()?.let { d -> current(d.choreId)?.name }
                    snack(Snack.Text(Notice.Dropped(marks.size, name)))
                } else if (undos.isNotEmpty()) {
                    snack(Snack.Text(Notice.UndoDropped(undos.size)))
                }
                load()
            }
        }
        scope.launch { outbox.refreshCount() }
    }

    /** The offline cache is a nice-to-have: a storage error must never break the list. */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun current(id: Long): Chore? = _state.value.chores.find { it.id == id }

    private fun setRow(c: Chore) {
        mutations++
        _state.update { s -> s.copy(chores = s.chores.map { if (it.id == c.id) c else it }) }
    }

    private fun snack(s: Snack?) = _state.update { it.copy(snack = s, snackSeq = it.snackSeq + 1) }

    private fun day(): LocalDate = today().also { d -> _state.update { if (it.today == d) it else it.copy(today = d) } }

    private suspend fun pendingMarks(): List<PendingDone> = outbox.marks().map { PendingDone(it.uuid, it.choreId, it.doneOn, it.note) }

    suspend fun load() {
        val seq = ++fetchSeq
        val mut = mutations
        fun stale() = seq != fetchSeq || mut != mutations
        try {
            val (h, list) = coroutineScope {
                val h = async { account.api.household(householdId) }
                val l = async { account.api.chores(householdId) }
                h.await() to l.await()
            }
            val queued = pendingMarks()
            if (stale()) return
            val d = day()
            _state.update {
                it.copy(household = h, chores = Chores.withPending(list, queued, account.me, d), loaded = true, offline = false, failed = false)
            }
            safely { cache.save(account, CachedList(h, list)) }
            if (!opened) {
                opened = true
                runCatching { onOpened(householdId) }
            }
            if (outbox.count.value > 0) outbox.kick()
        } catch (e: OfflineException) {
            if (stale()) return
            _state.update { it.copy(offline = true) }
            if (_state.value.loaded) return
            val c = safely { cache.load(account, householdId) }
            if (stale()) return
            if (c != null) {
                val d = day()
                val chores = Chores.withPending(Chores.rebase(c.chores, d), pendingMarks(), account.me, d)
                _state.update { it.copy(household = c.household, chores = chores, loaded = true) }
            } else {
                _state.update { it.copy(failed = true) }
            }
        } catch (e: ApiException) {
            if (stale()) return
            if (e.status == 404 || e.status == 403) {
                _state.update { it.copy(gone = true) }
            } else if (!_state.value.loaded) {
                _state.update { it.copy(failed = true) }
            }
        }
    }

    /** Pull to refresh: shows the spinner until the list is back. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        scope.launch {
            try {
                load()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** One tap on ✓: done today. A second tap on the same chore within a second, or while it is sent, is ignored. */
    fun tap(choreId: Long) {
        val c = current(choreId) ?: return
        val t = now()
        if (choreId in sending || t - (lastTap[choreId] ?: Long.MIN_VALUE / 2) < TAP_GUARD_MS) return
        sending += choreId
        lastTap[choreId] = t
        val send = mark(c, null, null)
        scope.launch {
            try {
                send()
            } finally {
                sending -= choreId
                lastTap[choreId] = now()
            }
        }
    }

    /** Shows the mark at once; the returned step sends it. */
    private fun mark(c: Chore, doneOn: String?, note: String?): suspend () -> Unit {
        val d = day()
        val on = doneOn ?: d.toString()
        val prev = current(c.id) ?: c
        val uuid = done.newId()
        setRow(Chores.applyCompletion(prev, on, account.me, d))
        snack(Snack.Done(prev, uuid, on, note, otherDate = true))
        return {
            val r = done.mark(c.id, on, note, uuid)
            if (r.rejected) setRow(prev)
        }
    }

    fun undo() {
        val s = _state.value.snack as? Snack.Done ?: return
        snack(null)
        val before = current(s.prev.id)
        setRow(s.prev)
        scope.launch {
            when (done.undo(s.uuid, s.prev.id)) {
                Done.Undo.Ok -> load()
                Done.Undo.Queued -> {}
                Done.Undo.Error -> {
                    if (before != null) setRow(before)
                    snack(Snack.Text(Notice.Failed))
                }
            }
        }
    }

    fun dismissSnack(seq: Long) {
        _state.update { if (it.snackSeq == seq) it.copy(snack = null) else it }
    }

    fun showNotice(n: Notice) = snack(Snack.Text(n))

    /** Long press: a new mark with another date or a note. */
    fun openSheet(choreId: Long) {
        val c = current(choreId) ?: return
        snack(null)
        _state.update { it.copy(sheet = DoneSheet(c, c, null, day().toString(), null)) }
    }

    /** "Other date" in the snackbar: changes the mark just made. */
    fun openSheetFromSnack() {
        val s = _state.value.snack as? Snack.Done ?: return
        val c = current(s.prev.id) ?: s.prev
        snack(null)
        _state.update { it.copy(sheet = DoneSheet(c, s.prev, s.uuid, s.doneOn, s.note)) }
    }

    fun closeSheet() = _state.update { it.copy(sheet = null) }

    fun saveSheet(doneOn: String, note: String?) {
        val s = _state.value.sheet ?: return
        _state.update { it.copy(sheet = null) }
        val n = note?.trim()?.takeIf { it.isNotEmpty() }
        if (s.uuid == null) {
            val send = mark(s.chore, doneOn, n)
            scope.launch { send() }
            return
        }
        val before = current(s.chore.id)
        setRow(Chores.applyCompletion(s.prev, doneOn, account.me, day()))
        scope.launch {
            when (done.change(s.uuid, doneOn, n)) {
                Done.Edit.Ok -> {
                    snack(Snack.Done(s.prev, s.uuid, doneOn, n, otherDate = false))
                    load()
                }
                Done.Edit.Offline -> {
                    if (before != null) setRow(before)
                    snack(Snack.Text(Notice.Connect))
                }
                Done.Edit.Error -> {
                    if (before != null) setRow(before)
                    snack(Snack.Text(Notice.Failed))
                }
            }
        }
    }

    companion object {
        const val TAP_GUARD_MS = 1000L
        const val SNACK_MS = 6000L
    }
}
