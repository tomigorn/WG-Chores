package ch.tomigorn.wgchores

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.tomigorn.wgchores.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The chore list against the fake server: quick done, Undo, the date sheet, offline marks. */
@RunWith(AndroidJUnit4::class)
class ChoreListFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<WgcApp>()

    @Before fun setUp() {
        fake.start()
        runBlocking { app.container.session.clearAll() }
    }

    @After fun tearDown() = fake.close()

    private fun ComposeTestRule.waitFor(text: String) =
        waitUntil(10_000) { runCatching { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }

    private fun ComposeTestRule.waitForTag(tag: String) =
        waitUntil(10_000) { runCatching { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            Thread.sleep(50)
        }
    }

    private fun signIn() {
        compose.waitForTag("server-url")
        compose.onNodeWithTag("server-url").performTextReplacement(fake.url)
        compose.onNodeWithTag("connect").performClick()
        compose.waitForTag("username")
        compose.onNodeWithTag("username").performTextReplacement("anna")
        compose.onNodeWithTag("password").performTextReplacement(fake.pw)
        compose.onNodeWithTag("submit").performScrollTo().performClick()
        compose.waitForTag("done-2")
    }

    @Test fun tapMarksDoneAndTheServerGetsIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            compose.onNodeWithTag("done-2").performClick()
            compose.waitForTag("snackbar")
            compose.waitFor("done today")
            waitUntil("completion on the server") { fake.created().size == 1 }
            assertEquals(2L, fake.created().values.single().choreId)
            assertEquals(fake.today.toString(), fake.created().values.single().doneOn)
            assertEquals(1L, fake.lastHousehold)
        }
    }

    @Test fun undoTakesTheMarkBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            compose.onNodeWithTag("done-2").performClick()
            compose.waitForTag("snack-undo")
            waitUntil("sent") { fake.created().size == 1 }
            compose.onNodeWithTag("snack-undo").performClick()
            waitUntil("deleted on the server") { fake.created().isEmpty() }
            compose.waitFor("Clean toilet")
        }
    }

    @Test fun longPressPicksAnotherDateWithANote() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            compose.onNodeWithTag("row-1").performTouchInput { longClick() }
            compose.waitForTag("sheet-day-1")
            compose.onNodeWithTag("sheet-day-1").performClick()
            compose.onNodeWithTag("sheet-note").performTextReplacement("used the new cleaner")
            compose.onNodeWithTag("sheet-save").performClick()
            waitUntil("completion on the server") { fake.created().size == 1 }
            val done = fake.created().values.single()
            assertEquals(1L, done.choreId)
            assertEquals(fake.today.minusDays(1).toString(), done.doneOn)
            assertEquals("used the new cleaner", done.note)
        }
    }

    @Test fun otherDateFromTheSnackbarChangesTheMark() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            compose.onNodeWithTag("done-2").performClick()
            compose.waitForTag("snack-other-date")
            waitUntil("sent") { fake.created().size == 1 }
            compose.onNodeWithTag("snack-other-date").performClick()
            compose.waitForTag("sheet-day-2")
            compose.onNodeWithTag("sheet-day-2").performClick()
            compose.onNodeWithTag("sheet-save").performClick()
            waitUntil("patched") { fake.created().values.single().doneOn == fake.today.minusDays(2).toString() }
            assertEquals(1, fake.created().size)
        }
    }

    @Test fun offlineMarkIsSyncedExactlyOnceAfterARestart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            fake.stop()
            compose.onNodeWithTag("done-2").performClick()
            compose.waitForTag("sync-chip")
            val queued = runBlocking { app.container.sync.account()!!.outbox.items() }
            assertEquals(1, queued.size)
        }
        assertTrue(fake.created().isEmpty())
        fake.restart()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag("chore-list")
            waitUntil("synced") { fake.created().size == 1 }
            waitUntil("outbox empty") { runBlocking { app.container.sync.account()!!.outbox.items() }.isEmpty() }
            Thread.sleep(2_000)
            assertEquals(1, fake.created().size)
            val posts = fake.requests.count { it.method == "POST" && it.url.encodedPath == "/api/v1/chores/2/completions" }
            assertTrue("posts: $posts", posts >= 1)
        }
    }
}
