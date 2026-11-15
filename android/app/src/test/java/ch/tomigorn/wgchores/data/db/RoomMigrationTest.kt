package ch.tomigorn.wgchores.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WgcDatabase::class.java)

    @Test fun version1QueueSurvivesTheEntryDateColumn() {
        helper.createDatabase("m.db", 1).apply {
            execSQL("INSERT INTO pending_deletes (uuid, account, chore_id, created_at) VALUES ('u', 'https://chores.example.com#1', 2, 10)")
            execSQL("INSERT INTO pending_marks (uuid, account, chore_id, done_on, note, tried, created_at) VALUES ('m', 'https://chores.example.com#1', 2, '2026-11-14', NULL, 0, 11)")
            close()
        }
        val db = helper.runMigrationsAndValidate("m.db", 2, true)
        db.query("SELECT uuid, entry_on FROM pending_deletes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("u", c.getString(0))
            assertTrue(c.isNull(1))
        }
        db.query("SELECT COUNT(*) FROM pending_marks").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }
}
