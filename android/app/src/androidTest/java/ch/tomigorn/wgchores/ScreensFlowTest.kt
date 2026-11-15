package ch.tomigorn.wgchores

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.testing.FakeServer
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Chore page, editing, invites, joining, account deletion and devices against the fake server. */
@RunWith(AndroidJUnit4::class)
class ScreensFlowTest {
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

    private fun click(tag: String) {
        compose.waitForTag(tag)
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
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

    private fun openMenu(item: String) {
        click("menu")
        compose.waitForTag(item)
        compose.onNodeWithTag(item).performClick()
    }

    @Test fun chorePageMarksChangesAndDeletesEntries() {
        fake.seedHistory(2, 3)
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            compose.onNodeWithTag("row-2").performClick()
            compose.waitForTag("chore-done")
            compose.waitFor("Usually done every ~7 days")
            click("chore-done")
            waitUntil("marked") { fake.created().size == 1 }
            assertEquals(fake.today.toString(), fake.created().values.single().doneOn)

            click("entry-hist-2-1")
            compose.waitForTag("sheet-note")
            compose.onNodeWithTag("sheet-day-1").performClick()
            compose.onNodeWithTag("sheet-note").performTextReplacement("fixed note")
            compose.onNodeWithTag("sheet-save").performClick()
            waitUntil("patched") { fake.completions["hist-2-1"]?.note == "fixed note" }
            assertEquals(fake.today.minusDays(1).toString(), fake.completions["hist-2-1"]?.doneOn)

            click("entry-hist-2-2")
            click("sheet-delete")
            click("confirm")
            waitUntil("deleted") { fake.completions["hist-2-2"] == null }
        }
    }

    @Test fun editChoreChangesTheInterval() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            compose.onNodeWithTag("row-2").performClick()
            click("chore-edit")
            compose.waitForTag("iv-1w")
            click("iv-1w")
            click("iv-3m")
            click("edit-save")
            waitUntil("saved") { fake.chores.single { it.id == 2L }.interval == "3m" }
            compose.waitFor("every 3 months")
            click("chore-edit")
            click("iv-3m")
            click("edit-save")
            waitUntil("unpicked") { fake.chores.single { it.id == 2L }.interval == null }
        }
    }

    @Test fun createInviteOpensTheShareSheet() {
        val sent = AtomicReference<Intent?>(null)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                sent.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                signIn()
                openMenu("menu-household-settings")
                click("invite-create")
                compose.waitForTag("invite-url")
                click("invite-share")
                waitUntil("share sheet") { sent.get() != null }
                @Suppress("DEPRECATION")
                val inner = sent.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, inner.action)
                assertTrue(inner.getStringExtra(Intent.EXTRA_TEXT)!!.startsWith(fake.url + "/invite/"))
                assertEquals(1, fake.invites.count { it.hid == 1L && it.token.startsWith("tok") })
            }
        } finally {
            inst.removeMonitor(monitor)
        }
    }

    @Test fun joinWithAPastedLink() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            openMenu("menu-households")
            compose.waitForTag("join-link")
            compose.onNodeWithTag("join-link").performTextReplacement("Join us: ${fake.url}/invite/join-me")
            click("join-show")
            compose.waitFor("Lake House")
            click("join-submit")
            waitUntil("joined") { fake.invites.single { it.token == "join-me" }.open.not() }
            compose.waitFor("Lake House")
        }
    }

    @Test fun deleteAccountGoesBackToTheServerPicker() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            openMenu("menu-account")
            click("account-delete")
            compose.waitForTag("delete-confirm")
            compose.onNodeWithTag("delete-confirm").performTextReplacement(fake.pw)
            compose.onNodeWithTag("confirm").performClick()
            compose.waitForTag("server-url")
            assertTrue(fake.deleted)
            assertTrue(app.container.auth.state.value is AuthState.PickServer)
            runBlocking { assertNull(app.container.session.read().server) }
        }
    }

    @Test fun signOutAnotherDevice() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            openMenu("menu-account")
            click("device-sign-out-d-web")
            waitUntil("signed out") { fake.devices.none { it.id == "d-web" } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("device-d-web")).fetchSemanticsNodes().isEmpty() }
            assertTrue(app.container.auth.state.value is AuthState.SignedIn)
            assertEquals(2, fake.devices.size)
        }
    }

    @Test fun languageSwitchesToGermanAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            openMenu("menu-settings")
            click("lang-de")
            compose.waitFor("Einstellungen")
            click("lang-system")
            compose.waitFor("Settings")
            click("notify-due")
            waitUntil("saved on the server") { !fake.notifyDue }
        }
    }
}
