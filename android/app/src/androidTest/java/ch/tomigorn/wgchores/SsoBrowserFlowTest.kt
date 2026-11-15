package ch.tomigorn.wgchores

import android.content.Intent
import android.content.pm.PackageManager
import androidx.browser.customtabs.CustomTabsClient
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import ch.tomigorn.wgchores.testing.FakeServer
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real browser on the emulator: confirm page, form POST, 303 to wgchores://auth, back in the app signed in. */
@RunWith(AndroidJUnit4::class)
class SsoBrowserFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<WgcApp>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before fun setUp() {
        fake.start()
        runBlocking { app.container.session.clearAll() }
    }

    @After fun tearDown() {
        device.pressHome()
        fake.close()
    }

    private fun ComposeTestRule.waitFor(text: String) =
        waitUntil(10_000) { runCatching { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }

    private fun ComposeTestRule.waitForTag(tag: String, ms: Long = 10_000) =
        waitUntil(ms) { runCatching { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }

    private fun browsers(): String = app.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")), PackageManager.MATCH_ALL)
        .joinToString { it.activityInfo.packageName }

    /** Chrome's first-run screens, if any, until the confirm page shows; null when it never does. */
    private fun confirmButton(): androidx.test.uiautomator.UiObject2? {
        val confirm = By.text(Pattern.compile("(?i)continue to the app"))
        val intro = By.text(Pattern.compile("(?i)(accept.*continue|no thanks|use without an account|got it)")).clickable(true)
        val end = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < end) {
            device.findObject(confirm)?.let { return it }
            try {
                device.findObject(intro)?.let {
                    android.util.Log.i("WgChoresTest", "browser intro: ${it.text}")
                    it.click()
                }
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
            }
            device.waitForIdle(500)
            Thread.sleep(500)
        }
        val dump = java.io.ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
        android.util.Log.i("WgChoresTest", "no confirm page, screen: " + Regex("text=\"([^\"]+)\"").findAll(dump).joinToString(" | ") { it.groupValues[1] })
        return null
    }

    @Test fun confirmPagePostAnd303ReachTheAppAndSignIn() {
        val browser = CustomTabsClient.getPackageName(app, null)
        assertNotNull("no Custom Tabs browser on the emulator; browsers: ${browsers()}", browser)
        val authTab = CustomTabsClient.isAuthTabSupported(app, browser!!)
        android.util.Log.i("WgChoresTest", "browser=$browser authTab=$authTab")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForTag("server-url")
            compose.onNodeWithTag("server-url").performTextReplacement(fake.url)
            compose.onNodeWithTag("connect").performClick()
            compose.waitFor("Sign in with Holy Grail")
            compose.onNodeWithTag("sso").performClick()
            val confirm = confirmButton()
            assertNotNull("confirm page not shown in $browser (authTab=$authTab)", confirm)
            confirm!!.click()
            compose.waitForTag("chore-list", 30_000)
            assertEquals(1, fake.confirmPosts.get())
            assertEquals(1, fake.issued.size)
        }
    }
}
