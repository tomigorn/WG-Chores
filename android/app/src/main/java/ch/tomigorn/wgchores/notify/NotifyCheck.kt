package ch.tomigorn.wgchores.notify

import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.data.Account
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Where notifications go; Android's notification manager in the app. */
interface NotifyShow {
    fun allowed(): Boolean
    fun due(account: String, household: Ref, title: String, body: String)
    fun done(account: String, household: Ref, title: String, body: String)
}

/** One background check: send waiting marks, ask the server, show what came back, then store the cursor (the acknowledgement). */
class NotifyCheck(
    private val account: suspend () -> Account?,
    private val flush: suspend () -> Unit,
    private val store: NotifyStore,
    private val show: NotifyShow,
    private val lang: () -> String,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Instant = Instant::now,
) {
    enum class Outcome { Done, Off, SignedOut, Retry }

    suspend fun run(): Outcome {
        val a = account() ?: return Outcome.SignedOut
        try {
            flush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return try {
            val me = a.api.me()
            store.saveFlags(a.key, me.notifyDone, me.notifyDue)
            when {
                !me.notifyDone && !me.notifyDue -> Outcome.Off
                !show.allowed() -> Outcome.Done
                else -> poll(a)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status == 401) Outcome.SignedOut else Outcome.Retry
        } catch (e: OfflineException) {
            Outcome.Retry
        } catch (e: Exception) {
            Outcome.Retry
        }
    }

    private suspend fun poll(a: Account): Outcome {
        val lang = lang()
        val started = now()
        val since = NotifyTexts.since(lang, store.lastCheck(a.key)?.let(Instant::ofEpochMilli), started, zone())
        var cursor = store.cursor(a.key)
        val done = LinkedHashMap<Long, Pair<Ref, MutableList<DoneEvent>>>()
        for (page in 1..MAX_PAGES) {
            val answer = try {
                a.api.poll(cursor)
            } catch (e: ApiException) {
                if (e.code != "invalid_cursor" || cursor == null) throw e
                a.api.poll(null)
            }
            for (g in answer.due) {
                val (title, body) = NotifyTexts.due(lang, g.household.name, g.chores.map { it.name })
                show.due(a.key, g.household, title, body)
            }
            val touched = LinkedHashSet<Long>()
            for (e in answer.done) {
                done.getOrPut(e.household.id) { e.household to mutableListOf() }.second += e
                touched += e.household.id
            }
            for (hid in touched) {
                val (household, events) = done.getValue(hid)
                show.done(a.key, household, household.name, NotifyTexts.done(lang, since, events.map { it.by to it.chore.name }))
            }
            store.saveCursor(a.key, answer.cursor)
            cursor = answer.cursor
            if (!answer.more) break
        }
        store.saveLastCheck(a.key, started.toEpochMilli())
        return Outcome.Done
    }

    companion object {
        const val MAX_PAGES = 5
    }
}
