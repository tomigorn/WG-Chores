package ch.tomigorn.wgchores.data.outbox

import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import java.util.UUID

interface CompletionApi {
    suspend fun complete(choreId: Long, uuid: String, doneOn: String, note: String?)
    suspend fun deleteCompletion(uuid: String)
    suspend fun patchCompletion(uuid: String, doneOn: String, note: String?)
}

/** Done marks through the outbox: mark, Undo, and changing the date or note of a just-made mark. */
class Done(private val outbox: Outbox, private val api: CompletionApi, private val uuid: () -> String = ::newUuid) {
    data class Mark(val uuid: String, val queued: Boolean, val rejected: Boolean)

    /** Queued: the Undo waits in the outbox and is sent once the server answers. */
    enum class Undo { Ok, Queued, Error }
    enum class Edit { Ok, Offline, Error }

    fun newId(): String = uuid()

    suspend fun mark(choreId: Long, doneOn: String, note: String?, id: String = uuid()): Mark {
        outbox.add(OutboxItem(id, choreId, doneOn, note))
        outbox.idle()
        val r = outbox.flush()
        if (r.dropped.any { it.uuid == id }) return Mark(id, queued = false, rejected = true)
        return Mark(id, queued = outbox.items().any { it.uuid == id }, rejected = false)
    }

    suspend fun undo(id: String, choreId: Long): Undo = remove(id, choreId, null)

    /** Deleting a history entry on the chore page; offline it waits in the outbox like an Undo. */
    suspend fun deleteEntry(id: String, choreId: Long, doneOn: String): Undo = remove(id, choreId, doneOn)

    private suspend fun remove(id: String, choreId: Long, entryOn: String?): Undo {
        var r = outbox.cancel(id, choreId, entryOn)
        if (r == Outbox.Cancel.InFlight) {
            outbox.idle()
            r = outbox.cancel(id, choreId, entryOn)
        }
        if (r == Outbox.Cancel.Removed) return Undo.Ok
        outbox.idle()
        val f = outbox.flush()
        if (f.dropped.any { it.uuid == id }) return Undo.Error
        return if (outbox.items().any { it.uuid == id }) Undo.Queued else Undo.Ok
    }

    suspend fun change(id: String, doneOn: String, note: String?): Edit {
        if (outbox.update(id, doneOn, note)) return Edit.Ok
        outbox.idle()
        if (outbox.update(id, doneOn, note)) return Edit.Ok
        return try {
            api.patchCompletion(id, doneOn, note)
            Edit.Ok
        } catch (e: OfflineException) {
            Edit.Offline
        } catch (e: ApiException) {
            Edit.Error
        }
    }

    companion object {
        fun newUuid(): String = UUID.randomUUID().toString()

        /** Sends one outbox item; a delete the server no longer knows, or a mark it already undid (410), counts as done. */
        fun sendWith(api: CompletionApi): suspend (OutboxItem) -> Unit = { i ->
            when (i.kind) {
                OutboxItem.Kind.Mark -> try {
                    api.complete(i.choreId, i.uuid, i.doneOn, i.note)
                } catch (e: ApiException) {
                    if (e.status != 410) throw e
                }
                OutboxItem.Kind.Delete -> try {
                    api.deleteCompletion(i.uuid)
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                }
            }
        }
    }
}
