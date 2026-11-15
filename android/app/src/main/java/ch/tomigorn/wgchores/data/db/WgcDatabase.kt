package ch.tomigorn.wgchores.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import ch.tomigorn.wgchores.data.outbox.OutboxItem
import ch.tomigorn.wgchores.data.outbox.OutboxStore

/** A done mark not yet confirmed by the server; [account] = server + user id. */
@Entity(tableName = "pending_marks", indices = [Index("account")])
data class PendingMark(
    @PrimaryKey val uuid: String,
    val account: String,
    @ColumnInfo(name = "chore_id") val choreId: Long,
    @ColumnInfo(name = "done_on") val doneOn: String,
    val note: String?,
    val tried: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** An Undo of a mark the server may already have. */
@Entity(tableName = "pending_deletes", indices = [Index("account")])
data class PendingDelete(
    @PrimaryKey val uuid: String,
    val account: String,
    @ColumnInfo(name = "chore_id") val choreId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Last answers kept for offline viewing (chore lists, the signed-in user). */
@Entity(tableName = "cache")
data class CacheEntry(@PrimaryKey val key: String, val json: String, @ColumnInfo(name = "saved_at") val savedAt: Long)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM pending_marks WHERE account = :account ORDER BY created_at, uuid")
    suspend fun marks(account: String): List<PendingMark>

    @Query("SELECT * FROM pending_deletes WHERE account = :account ORDER BY created_at, uuid")
    suspend fun deletes(account: String): List<PendingDelete>

    @Upsert suspend fun putMark(m: PendingMark)
    @Upsert suspend fun putDelete(d: PendingDelete)

    @Query("DELETE FROM pending_marks WHERE account = :account AND uuid = :uuid")
    suspend fun deleteMark(account: String, uuid: String)

    @Query("DELETE FROM pending_deletes WHERE account = :account AND uuid = :uuid")
    suspend fun deleteDelete(account: String, uuid: String)

    @Query("DELETE FROM pending_marks WHERE account = :account")
    suspend fun clearMarks(account: String)

    @Query("DELETE FROM pending_deletes WHERE account = :account")
    suspend fun clearDeletes(account: String)

    @Transaction
    suspend fun putItem(account: String, i: OutboxItem) {
        if (i.kind == OutboxItem.Kind.Mark) {
            deleteDelete(account, i.uuid)
            putMark(PendingMark(i.uuid, account, i.choreId, i.doneOn, i.note, i.tried, i.at))
        } else {
            deleteMark(account, i.uuid)
            putDelete(PendingDelete(i.uuid, account, i.choreId, i.at))
        }
    }

    @Transaction
    suspend fun remove(account: String, uuid: String) {
        deleteMark(account, uuid)
        deleteDelete(account, uuid)
    }

    @Transaction
    suspend fun clear(account: String) {
        clearMarks(account)
        clearDeletes(account)
    }
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key")
    suspend fun get(key: String): CacheEntry?

    @Upsert suspend fun put(e: CacheEntry)

    @Query("DELETE FROM cache WHERE `key` LIKE :prefix || '%'")
    suspend fun clearPrefix(prefix: String)
}

@Database(entities = [PendingMark::class, PendingDelete::class, CacheEntry::class], version = 1, exportSchema = false)
abstract class WgcDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun cache(): CacheDao

    companion object {
        fun open(context: Context): WgcDatabase = Room.databaseBuilder(context, WgcDatabase::class.java, "wgc.db").build()
    }
}

class RoomOutboxStore(private val dao: OutboxDao, private val account: String) : OutboxStore {
    override suspend fun all(): List<OutboxItem> {
        val marks = dao.marks(account).map { OutboxItem(it.uuid, it.choreId, it.doneOn, it.note, OutboxItem.Kind.Mark, it.tried, it.createdAt) }
        val deletes = dao.deletes(account).map { OutboxItem(it.uuid, it.choreId, "", null, OutboxItem.Kind.Delete, true, it.createdAt) }
        return (marks + deletes).sortedWith(compareBy({ it.at }, { it.uuid }))
    }

    override suspend fun put(item: OutboxItem) = dao.putItem(account, item)

    override suspend fun delete(uuid: String) = dao.remove(account, uuid)

    override suspend fun clear() = dao.clear(account)
}
