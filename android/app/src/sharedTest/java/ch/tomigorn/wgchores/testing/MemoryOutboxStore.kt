package ch.tomigorn.wgchores.testing

import ch.tomigorn.wgchores.data.outbox.OutboxItem
import ch.tomigorn.wgchores.data.outbox.OutboxStore

class MemoryOutboxStore : OutboxStore {
    private val items = LinkedHashMap<String, OutboxItem>()

    override suspend fun all(): List<OutboxItem> = synchronized(items) { items.values.sortedBy { it.at } }

    override suspend fun put(item: OutboxItem) {
        synchronized(items) { items[item.uuid] = item }
    }

    override suspend fun delete(uuid: String) {
        synchronized(items) { items.remove(uuid) }
    }

    override suspend fun clear() = synchronized(items) { items.clear() }
}
