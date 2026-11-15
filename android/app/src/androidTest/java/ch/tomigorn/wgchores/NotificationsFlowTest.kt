package ch.tomigorn.wgchores

import android.Manifest
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.hasTestTag
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
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.notify.NotifyScheduler
import ch.tomigorn.wgchores.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The background check run by WorkManager's test driver against the fake server: notifications posted, a tap opens the household. */
@RunWith(AndroidJUnit4::class)
class NotificationsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<WgcApp>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        fake.start()
        runBlocking {
            app.container.session.clearAll()
            app.container.notifyStore.forgetChecks()
            app.container.notifyStore.saveSignature(null)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(app.workerFactory).build())
    }

    @After fun tearDown() {
        nm.cancelAll()
        fake.close()
    }

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

    private fun waiting(): List<WorkInfo> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(NotifyScheduler.NAME).get().filter { it.state == WorkInfo.State.ENQUEUED }

    /** Runs the waiting background check now. */
    private fun runCheck() {
        waitUntil("a check is waiting") { waiting().size == 1 }
        val id = waiting().single().id
        val driver = WorkManagerTestInitHelper.getTestDriver(app)!!
        driver.setAllConstraintsMet(id)
        driver.setInitialDelayMet(id)
        waitUntil("the next check is waiting") { waiting().any { it.id != id } }
    }

    private fun ours(kind: String): List<StatusBarNotification> = nm.activeNotifications.filter { it.tag?.startsWith("$kind|") == true }
    private fun StatusBarNotification.title() = notification.extras.getCharSequence("android.title").toString()
    private fun StatusBarNotification.text() = notification.extras.getCharSequence("android.text").toString()

    @Test fun dueAndDoneNotificationsAndATapOpensTheHousehold() {
        fake.chores += Chore(id = 20, householdId = 2, name = "Water plants", interval = "1w")
        fake.dueChores += listOf(2L, 20L)
        ActivityScenario.launch(MainActivity::class.java).use {
            signIn()
            runCheck()
            waitUntil("due notifications") { ours("due").size == 2 }
            val due = ours("due").associate { it.title() to it.text() }
            assertEquals(mapOf("Clean toilet is due" to "WG Zürich", "Water plants is due" to "Family"), due)

            fake.doneBy("Sam", 6)
            runCheck()
            waitUntil("done notification") { ours("done").isNotEmpty() }
            val done = ours("done").single()
            assertEquals("WG Zürich", done.title())
            assertTrue(done.text(), done.text().endsWith("Sam ✓ Wipe stove"))
            assertTrue(fake.dueChores.isEmpty())

            ours("due").single { it.title() == "Water plants is due" }.notification.contentIntent.send()
            compose.waitForTag("done-20")
        }
    }
}
