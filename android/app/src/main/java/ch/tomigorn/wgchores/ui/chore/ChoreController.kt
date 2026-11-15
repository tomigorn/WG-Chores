package ch.tomigorn.wgchores.ui.chore

import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.data.CachedChore
import ch.tomigorn.wgchores.data.outbox.Done
import ch.tomigorn.wgchores.data.outbox.OutboxItem
import ch.tomigorn.wgchores.domain.By
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Chores
import ch.tomigorn.wgchores.domain.Completion
import ch.tomigorn.wgchores.domain.Days
import ch.tomigorn.wgchores.domain.PendingDone
import ch.tomigorn.wgchores.ui.list.Notice
import ch.tomigorn.wgchores.ui.list.Snack
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface ChoreCache {
    suspend fun save(a: Account, entry: CachedChore)
    suspend fun load(a: Account, id: Long): CachedChore?
}

/** One history line; [waiting] = a done mark still in the outbox. */
data class Entry(val id: String, val doneOn: String, val note: String?, val by: By, val waiting: Boolean)

sealed interface EntrySheet {
    data object New : EntrySheet
    data class Edit(val entry: Entry) : EntrySheet
}

enum class Phase { Loading, Ready, Failed, Gone }

data class ChoreUi(
    val today: LocalDate,
    val chore: Chore? = null,
    val entries: List<Entry> = emptyList(),
    val next: String? = null,
    val phase: Phase = Phase.Loading,
    val offline: Boolean = false,
    val loadingMore: Boolean = false,
    val snack: Snack? = null,
    val snackSeq: Long = 0,
    val sheet: EntrySheet? = null,
    val confirmDelete: Entry? = null,
)

/** The chore page: last done, history paged on scroll, done marks, changing or deleting an entry. */
class ChoreController(
    private val account: Account,
    val choreId: Long,
    private val cache: ChoreCache,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = { Days.today(account.tz) },
) {
    private val outbox = account.outbox
    private val done: Done = account.done
    private val _state = MutableStateFlow(ChoreUi(today()))
    val state: StateFlow<ChoreUi> = _state

    private var server: Chore? = null
    private var items: List<Completion> = emptyList()
    private var queued: List<OutboxItem> = emptyList()
    private var seq = 0
    private var mutations = 0
    private var sending = false

    init {
        scope.launch {
            outbox.flushed.collect { r -> if (r.sent > 0 || r.dropped.isNotEmpty()) load() }
        }
    }

    private fun day(): LocalDate = today().also { d -> _state.update { if (it.today == d) it else it.copy(today = d) } }

    private fun snack(s: Snack?) = _state.update { it.copy(snack = s, snackSeq = it.snackSeq + 1) }

    private fun note(id: Int, arg: String? = null) = snack(Snack.Text(Notice.Res(id, arg)))

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun refreshQueued() {
        queued = outbox.marks().filter { it.choreId == choreId }
    }

    /** Recomputes the chore (with waiting marks) and the history lines. */
    private fun show(extra: (ChoreUi) -> ChoreUi = { it }) {
        val d = day()
        val pending = queued.map { PendingDone(it.uuid, it.choreId, it.doneOn, it.note) }
        val c = server?.let { Chores.withPending(listOf(it), pending, account.me, d).single() }
        val waiting = queued.sortedByDescending { it.doneOn }.map { Entry(it.uuid, it.doneOn, it.note, account.me, waiting = true) }
        val sent = items.filter { i -> queued.none { it.uuid == i.id } }.map { Entry(it.id, it.doneOn, it.note, it.by, waiting = false) }
        _state.update { extra(it.copy(chore = c, entries = waiting + sent)) }
    }

    suspend fun load() {
        val s = ++seq
        val mut = mutations
        fun stale() = s != seq || mut != mutations
        try {
            val (c, page) = coroutineScope {
                val c = async { account.api.chore(choreId) }
                val p = async { account.api.history(choreId, limit = PAGE) }
                c.await() to p.await()
            }
            refreshQueued()
            if (stale()) return
            server = c
            items = page.items
            show { it.copy(next = page.next, phase = Phase.Ready, offline = false) }
            safely { cache.save(account, CachedChore(c, page.items)) }
        } catch (e: OfflineException) {
            if (stale()) return
            _state.update { it.copy(offline = true) }
            if (_state.value.phase == Phase.Ready) return
            val cached = safely { cache.load(account, choreId) }
            refreshQueued()
            if (stale()) return
            if (cached == null) {
                _state.update { it.copy(phase = Phase.Failed) }
                return
            }
            server = Chores.rebase(listOf(cached.chore), day()).single()
            items = cached.items
            show { it.copy(next = null, phase = Phase.Ready) }
        } catch (e: ApiException) {
            if (stale()) return
            if (e.status == 404 || e.status == 403) _state.update { it.copy(phase = Phase.Gone) }
            else if (_state.value.phase == Phase.Loading) _state.update { it.copy(phase = Phase.Failed) }
        }
    }

    /** The next page of older entries, when there is one. */
    fun more() {
        val s = _state.value
        val next = s.next ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val page = account.api.history(choreId, before = next, limit = PAGE)
                items = items + page.items.filter { p -> items.none { it.id == p.id } }
                show { it.copy(next = page.next) }
            } catch (e: OfflineException) {
                _state.update { it.copy(offline = true) }
            } catch (e: ApiException) {
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** "Done today", or a new entry from the sheet with another date or a note. */
    private fun mark(doneOn: String?, note: String?, otherDate: Boolean) {
        val c = _state.value.chore ?: return
        if (sending) return
        sending = true
        mutations++
        val on = doneOn ?: day().toString()
        val uuid = done.newId()
        queued = queued + OutboxItem(uuid, choreId, on, note)
        show()
        snack(Snack.Done(c, uuid, on, note, otherDate))
        scope.launch {
            try {
                val r = done.mark(choreId, on, note, uuid)
                refreshQueued()
                show()
                if (r.rejected) snack(Snack.Text(Notice.Dropped(1, c.name, 0)))
                if (!r.queued) load()
            } finally {
                sending = false
            }
        }
    }

    fun tap() = mark(null, null, otherDate = true)

    fun undo() {
        val s = _state.value.snack as? Snack.Done ?: return
        snack(null)
        mutations++
        items = items.filter { it.id != s.uuid }
        queued = queued.filter { it.uuid != s.uuid }
        show()
        scope.launch {
            when (done.undo(s.uuid, choreId)) {
                Done.Undo.Ok -> load()
                Done.Undo.Queued -> {
                    refreshQueued()
                    show()
                }
                Done.Undo.Error -> {
                    snack(Snack.Text(Notice.Failed))
                    load()
                }
            }
        }
    }

    fun dismissSnack(seq: Long) = _state.update { if (it.snackSeq == seq) it.copy(snack = null) else it }

    fun openNew() {
        snack(null)
        day()
        _state.update { it.copy(sheet = EntrySheet.New) }
    }

    fun openEntry(e: Entry) {
        day()
        _state.update { it.copy(sheet = EntrySheet.Edit(e)) }
    }

    /** "Other date" in the bar: the mark just made. */
    fun otherDate() {
        val s = _state.value.snack as? Snack.Done ?: return
        snack(null)
        val e = _state.value.entries.find { it.id == s.uuid } ?: Entry(s.uuid, s.doneOn, s.note, account.me, waiting = false)
        openEntry(e)
    }

    fun closeSheet() = _state.update { it.copy(sheet = null) }

    fun saveSheet(doneOn: String, note: String?) {
        val sheet = _state.value.sheet ?: return
        _state.update { it.copy(sheet = null) }
        val n = note?.trim()?.takeIf { it.isNotEmpty() }
        when (sheet) {
            EntrySheet.New -> mark(doneOn, n, otherDate = false)
            is EntrySheet.Edit -> {
                mutations++
                scope.launch {
                    when (done.change(sheet.entry.id, doneOn, n)) {
                        Done.Edit.Ok -> {
                            note(R.string.entry_saved)
                            refreshQueued()
                            show()
                            load()
                        }
                        Done.Edit.Offline -> snack(Snack.Text(Notice.Connect))
                        Done.Edit.Error -> snack(Snack.Text(Notice.Failed))
                    }
                }
            }
        }
    }

    fun askDelete() {
        val e = (_state.value.sheet as? EntrySheet.Edit)?.entry ?: return
        _state.update { it.copy(sheet = null, confirmDelete = e) }
    }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = null) }

    fun delete() {
        val e = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null) }
        mutations++
        scope.launch {
            val r = done.undo(e.id, choreId)
            refreshQueued()
            if (r == Done.Undo.Error) {
                snack(Snack.Text(Notice.Failed))
                return@launch
            }
            items = items.filter { it.id != e.id }
            show()
            note(R.string.entry_deleted)
            if (r == Done.Undo.Ok) load()
        }
    }

    fun restore() {
        mutations++
        scope.launch {
            try {
                server = account.api.restoreChore(choreId)
                show()
                note(R.string.hh_restored, server?.name)
            } catch (e: OfflineException) {
                snack(Snack.Text(Notice.Connect))
            } catch (e: ApiException) {
                snack(Snack.Text(Notice.Failed))
            }
        }
    }

    companion object {
        const val PAGE = 20
    }
}
