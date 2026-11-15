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

    private fun ComposeTestRule.waitFor(text: String) =
        waitUntil(10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeTestRule.waitForTag(tag: String) =
        waitUntil(10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

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
            compose.waitFor("Signed in as Jane Doe")
            assertEquals(1, fake.issued.size)
            val bearer = fake.issued.single()

            compose.onNodeWithTag("sign-out").performScrollTo().performClick()
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
            compose.waitFor("Signed in as Jane Doe")
        }
        fake.close()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitFor("Signed in as Jane Doe")
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
