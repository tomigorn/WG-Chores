package ch.tomigorn.wgchores.data

import ch.tomigorn.wgchores.api.ApiClient
import ch.tomigorn.wgchores.data.db.CacheEntry
import ch.tomigorn.wgchores.data.db.RoomOutboxStore
import ch.tomigorn.wgchores.data.db.WgcDatabase
import ch.tomigorn.wgchores.data.outbox.Done
import ch.tomigorn.wgchores.data.outbox.Outbox
import ch.tomigorn.wgchores.data.outbox.OutboxItem
import ch.tomigorn.wgchores.domain.By
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Household
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One signed-in user on one server, with its own outbox. */
class Account(val server: String, val me: By, val tz: String, val api: ApiClient, val outbox: Outbox, val done: Done) {
    val key: String get() = keyOf(server, me.id)

    /** Items dropped from the outbox while no list was on screen; the next shown list says so. */
    val unseenDrops: MutableList<OutboxItem> = java.util.Collections.synchronizedList(mutableListOf())

    companion object {
        fun keyOf(server: String, userId: Long) = "$server#$userId"
    }
}

@Serializable
data class CachedChore(val chore: Chore, val items: List<ch.tomigorn.wgchores.domain.Completion>)

@Serializable
data class CachedList(val household: Household, val chores: List<Chore>)

/** Accounts, their outboxes and the offline cache; flushes from app start, network changes and the background check. */
class Sync(
    private val session: SessionStore,
    private val api: (String) -> ApiClient,
    private val db: WgcDatabase,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val accounts = HashMap<String, Account>()
    private val json = Json { ignoreUnknownKeys = true }

    /** The signed-in account, or null when signed out or the user id is not known yet. */
    suspend fun account(): Account? {
        val s = session.read()
        val server = s.server ?: return null
        if (s.token == null) return null
        val id = s.userId ?: return null
        val key = Account.keyOf(server, id)
        val me = By(id, s.userName.orEmpty())
        return synchronized(accounts) {
            accounts[key]?.takeIf { it.me == me && it.tz == (s.config?.tz ?: "UTC") }?.let { return it }
            val client = api(server)
            val outbox = accounts[key]?.outbox ?: Outbox(RoomOutboxStore(db.outbox(), key), Done.sendWith(client), scope, now)
            Account(server, me, s.config?.tz ?: "UTC", client, outbox, Done(outbox, client)).also { accounts[key] = it }
        }
    }

    suspend fun flush() {
        account()?.outbox?.flush()
    }

    fun kick() {
        scope.launch { runCatching { flush() } }
    }

    private fun listKey(a: Account, hid: Long) = "list:${a.key}:$hid"

    suspend fun saveList(a: Account, list: CachedList) {
        db.cache().put(CacheEntry(listKey(a, list.household.id), json.encodeToString(CachedList.serializer(), list), now()))
    }

    suspend fun loadList(a: Account, hid: Long): CachedList? =
        db.cache().get(listKey(a, hid))?.let { runCatching { json.decodeFromString(CachedList.serializer(), it.json) }.getOrNull() }

    private fun choreKey(a: Account, id: Long) = "chore:${a.key}:$id"

    suspend fun saveChore(a: Account, c: CachedChore) {
        db.cache().put(CacheEntry(choreKey(a, c.chore.id), json.encodeToString(CachedChore.serializer(), c), now()))
    }

    suspend fun loadChore(a: Account, id: Long): CachedChore? =
        db.cache().get(choreKey(a, id))?.let { runCatching { json.decodeFromString(CachedChore.serializer(), it.json) }.getOrNull() }

    /** Sign-out: the account's waiting marks and cached lists go. */
    suspend fun forget(a: Account) {
        a.outbox.clear()
        db.cache().clearPrefix("list:${a.key}:")
        db.cache().clearPrefix("chore:${a.key}:")
        synchronized(accounts) { accounts.remove(a.key) }
    }
}
