package ch.tomigorn.wgchores

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Back from a chore page or a household lands where the list was scrolled to, also after a refresh and an activity restart. */
@RunWith(AndroidJUnit4::class)
class ScrollRestoreFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<WgcApp>()

    @Before fun setUp() {
        fake.start()
        runBlocking { app.container.session.clearAll() }
        for (i in 100L until 130L) fake.chores += Chore(id = i, householdId = 1, name = "Extra chore $i", category = "Extra", interval = "1w")
        for (i in 3L..20L) fake.households += Household(i, "Household $i", 1)
    }

    @After fun tearDown() = fake.close()

    private fun ComposeTestRule.waitForTag(tag: String) =
        waitUntil(10_000) { runCatching { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }

    private fun top(tag: String): Dp {
        compose.waitForTag(tag)
        compose.waitForIdle()
        return compose.onNodeWithTag(tag).getBoundsInRoot().top
    }

    private fun assertSameTop(tag: String, before: Dp) {
        val after = top(tag)
        assertTrue("$tag moved from $before to $after", (after - before).value in -2f..2f)
    }

    private fun loads() = fake.requests.count { it.url.encodedPath == "/api/v1/households/1/chores" }

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

    private fun openMenu(item: String) {
        compose.waitForTag("menu")
        compose.onNodeWithTag("menu").performClick()
        compose.waitForTag(item)
        compose.onNodeWithTag(item).performClick()
    }

    @Test fun choreListKeepsItsPlaceAfterAChorePageRefreshAndRestart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            signIn()
            compose.onNodeWithTag("chore-list").performScrollToKey(125L)
            val before = top("row-125")
            assertTrue("list did not scroll", before.value > 0f)
            compose.onNodeWithTag("row-125").performClick()
            compose.waitForTag("chore-page")
            // the list reloads on return; a new chore above the visible part must not move it
            val loadsBefore = loads()
            fake.chores += Chore(id = 140, householdId = 1, name = "Brand new chore", category = "Aaa first", interval = "1w")
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitUntil(10_000) { loads() > loadsBefore }
            Thread.sleep(500)
            assertSameTop("row-125", before)
            scenario.recreate()
            assertSameTop("row-125", before)
        }
    }

    @Test fun householdsListKeepsItsPlaceAfterOpeningAHousehold() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            signIn()
            openMenu("menu-households")
            compose.waitForTag("household-18")
            compose.onNodeWithTag("household-18").performScrollTo()
            val before = top("household-18")
            assertTrue("list did not scroll", before.value > 0f)
            compose.onNodeWithTag("household-18").performClick()
            compose.waitUntil(10_000) { runCatching { compose.onAllNodes(hasText("Household 18")).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            openMenu("menu-households")
            assertSameTop("household-18", before)
            scenario.recreate()
            assertSameTop("household-18", before)
            assertEquals(20, fake.households.size)
        }
    }
}
