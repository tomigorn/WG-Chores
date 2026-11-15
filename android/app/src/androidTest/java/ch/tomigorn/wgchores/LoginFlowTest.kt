package ch.tomigorn.wgchores

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
import ch.tomigorn.wgchores.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<WgcApp>()

    @Before fun setUp() {
        fake.start()
        runBlocking { app.container.session.clearAll() }
    }

    @After fun tearDown() = fake.close()

    private fun finishAll() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        for (stage in listOf(androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.PAUSED, androidx.test.runner.lifecycle.Stage.STOPPED)) {
            monitor.getActivitiesInStage(stage).forEach { it.finish() }
        }
    }

    private fun ComposeTestRule.waitFor(text: String) =
        waitUntil(10_000) { runCatching { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }

    private fun ComposeTestRule.waitForTag(tag: String) =
        waitUntil(10_000) { runCatching { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }

    private fun signIn(pw: String) {
        compose.waitForTag("server-url")
        compose.onNodeWithTag("server-url").performTextReplacement(fake.url)
        compose.onNodeWithTag("connect").performClick()
        compose.waitFor("Sign in with Holy Grail")
        compose.onNodeWithTag("username").performTextReplacement("anna")
        compose.onNodeWithTag("password").performTextReplacement(pw)
        compose.onNodeWithTag("submit").performScrollTo().performClick()
    }

    @Test fun signInAndOut() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn(fake.pw)
            compose.waitForTag("chore-list")
            compose.waitForTag("household-switch")
            assertEquals(1, fake.issued.size)
            val bearer = fake.issued.single()

            compose.onNodeWithTag("menu").performClick()
            compose.waitForTag("sign-out")
            compose.onNodeWithTag("sign-out").performClick()
            compose.waitFor("Sign in with Holy Grail")
            assertTrue(fake.issued.isEmpty())
            assertEquals("Bearer $bearer", fake.lastRequest("/api/v1/auth/logout")!!.headers["Authorization"])
        }
    }

    @Test fun wrongPasswordShowsTheServerError() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn("not the password")
            compose.waitFor("Wrong username or password.")
        }
    }

    @Test fun staysSignedInAfterARestartWithoutNetwork() {
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn(fake.pw)
            compose.waitFor("Clean toilet")
        }
        fake.close()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitFor("Offline – showing the last known list")
            compose.waitFor("Clean toilet")
        }
    }

    @Test fun callbackToTheLauncherActivityIsIgnored() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag("server-url")
            compose.onNodeWithTag("server-url").performTextReplacement(fake.url)
            compose.onNodeWithTag("connect").performClick()
            compose.waitFor("Sign in with Holy Grail")
        }
        runBlocking { app.container.session.saveVerifier("v".repeat(43)) }
        val code = fake.issueSsoCode(ch.tomigorn.wgchores.auth.Pkce.challenge("v".repeat(43)))
        val intent = android.content.Intent(app, MainActivity::class.java)
            .setData(android.net.Uri.parse("wgchores://auth?code=$code"))
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitFor("Sign in with Holy Grail")
            Thread.sleep(1_000)
            compose.waitFor("Sign in with Holy Grail")
        }
        assertTrue(fake.issued.isEmpty())
        runBlocking { assertEquals("v".repeat(43), app.container.session.takeVerifier()) }
    }

    @Test fun callbackThroughTheFallbackAliasSignsIn() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag("server-url")
            compose.onNodeWithTag("server-url").performTextReplacement(fake.url)
            compose.onNodeWithTag("connect").performClick()
            compose.waitFor("Sign in with Holy Grail")
        }
        val verifier = "w".repeat(43)
        runBlocking { app.container.session.saveVerifier(verifier) }
        val alias = android.content.ComponentName(app, "${app.packageName}.SsoCallback")
        app.packageManager.setComponentEnabledSetting(alias, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        val code = fake.issueSsoCode(ch.tomigorn.wgchores.auth.Pkce.challenge(verifier))
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("wgchores://auth?code=$code"))
            .setComponent(alias)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        compose.waitForTag("chore-list")
        assertEquals(1, fake.issued.size)
        finishAll()
        Thread.sleep(1_000)
        assertEquals(
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            app.packageManager.getComponentEnabledSetting(alias),
        )
    }

    @Test fun changeServerForgetsTheServer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag("server-url")
            compose.onNodeWithTag("server-url").performTextReplacement(fake.url)
            compose.onNodeWithTag("connect").performClick()
            compose.waitForTag("change-server")
            compose.onNodeWithTag("change-server").performClick()
            compose.waitForTag("server-url")
            runBlocking { assertEquals(null, app.container.session.read().server) }
        }
    }
}
