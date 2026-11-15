package ch.tomigorn.wgchores.ui.chore

import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.api.ApiClient
import ch.tomigorn.wgchores.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ChoreEditorTest {
    private val fake = FakeServer().start()
    private val bearer = fake.token()
    private val api = ApiClient(fake.url, bearer = { bearer })

    @After fun stop() = fake.close()

    private fun calls() = fake.requests.filter { it.method == "PATCH" }.map { it.url.encodedPath }

    @Test fun theChoreIsSavedBeforeTheCategoryIsRenamedForTheOthers() = runBlocking {
        val r = ChoreEditor.save(api, choreId = 2, householdId = 1, original = "Bathroom", name = "Clean toilet", category = "Bath", interval = "1w", renameAll = true)
        assertEquals(ChoreEditor.Result.Saved, r)
        assertEquals(listOf("/api/v1/chores/2", "/api/v1/households/1/categories"), calls())
        assertEquals(listOf("Bath"), fake.chores.filter { it.householdId == 1L && it.id in 1L..3L }.map { it.category }.distinct())
    }

    @Test fun aFailedRenameSaysTheChoreIsSavedButTheOthersKeepTheirCategory() = runBlocking {
        fake.refuseRename = true
        val r = ChoreEditor.save(api, choreId = 2, householdId = 1, original = "Bathroom", name = "Clean toilet", category = "Bath", interval = "1w", renameAll = true)
        assertEquals(ChoreEditor.Result.RenameFailed(R.string.error_generic), r)
        assertEquals("Bath", fake.chores.single { it.id == 2L }.category)
        assertEquals("Bathroom", fake.chores.single { it.id == 1L }.category)
    }

    @Test fun aFailedSaveRenamesNothing() = runBlocking {
        fake.down = true
        val r = ChoreEditor.save(api, choreId = 2, householdId = 1, original = "Bathroom", name = "Clean toilet", category = "Bath", interval = "1w", renameAll = true)
        assertEquals(ChoreEditor.Result.SaveFailed(R.string.done_connect), r)
        assertEquals(listOf("/api/v1/chores/2"), calls())
    }
}
