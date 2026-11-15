package ch.tomigorn.wgchores.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.tomigorn.wgchores.data.outbox.Outbox
import ch.tomigorn.wgchores.data.outbox.OutboxItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomOutboxStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WgcDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val anna = RoomOutboxStore(db.outbox(), "https://chores.example.com#1")
    private val sam = RoomOutboxStore(db.outbox(), "https://chores.example.com#2")

    @After fun close() = db.close()

    @Test fun marksAndDeletesLiveInTheirOwnTablesInOrder() = runBlocking {
        anna.put(OutboxItem("b", 2, "2026-11-13", "note", at = 20))
        anna.put(OutboxItem("a", 1, "2026-11-14", null, at = 10))
        anna.put(OutboxItem("c", 3, "", null, OutboxItem.Kind.Delete, at = 15))
        assertEquals(listOf("a", "c", "b"), anna.all().map { it.uuid })
        assertEquals(2, db.outbox().marks("https://chores.example.com#1").size)
        assertEquals(1, db.outbox().deletes("https://chores.example.com#1").size)
        assertEquals("note", anna.all().last().note)
    }

    @Test fun aMarkTurnedIntoADeleteLeavesTheMarkTable() = runBlocking {
        anna.put(OutboxItem("a", 1, "2026-11-14", null, tried = true, at = 10))
        anna.put(OutboxItem("a", 1, "", null, OutboxItem.Kind.Delete, at = 11))
        assertTrue(db.outbox().marks("https://chores.example.com#1").isEmpty())
        assertEquals(OutboxItem.Kind.Delete, anna.all().single().kind)
        anna.delete("a")
        assertTrue(anna.all().isEmpty())
    }

    @Test fun eachAccountHasItsOwnOutbox() = runBlocking {
        anna.put(OutboxItem("a", 1, "2026-11-14", null, at = 10))
        sam.put(OutboxItem("s", 1, "2026-11-14", null, at = 10))
        anna.clear()
        assertTrue(anna.all().isEmpty())
        assertEquals(listOf("s"), sam.all().map { it.uuid })
    }

    @Test fun outboxStateMachineRunsOnRoom() = runBlocking {
        val sent = mutableListOf<String>()
        val o = Outbox(anna, send = { sent += it.uuid }, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        o.add(OutboxItem("a", 1, "2026-11-14", null))
        assertEquals(1, o.flush().sent)
        assertEquals(listOf("a"), sent)
        assertTrue(anna.all().isEmpty())
    }

    @Test fun anEntryDeleteKeepsTheEntrysDate() = runBlocking {
        anna.put(OutboxItem("e", 1, "", null, OutboxItem.Kind.Delete, at = 10, entryOn = "2026-10-19"))
        anna.put(OutboxItem("u", 1, "", null, OutboxItem.Kind.Delete, at = 11))
        assertEquals(listOf("2026-10-19", null), anna.all().map { it.entryOn })
    }
}
