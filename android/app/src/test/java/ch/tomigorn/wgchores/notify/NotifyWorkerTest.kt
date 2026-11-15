package ch.tomigorn.wgchores.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Intent
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import ch.tomigorn.wgchores.MainActivity
import ch.tomigorn.wgchores.api.ApiClient
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.data.outbox.Done
import ch.tomigorn.wgchores.data.outbox.Outbox
import ch.tomigorn.wgchores.testing.FakeServer
import ch.tomigorn.wgchores.testing.MemoryOutboxStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotifyWorkerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fake = FakeServer().start()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bearer = fake.token()
    private val api = ApiClient(fake.url, bearer = { bearer })
    private val outbox = Outbox(MemoryOutboxStore(), Done.sendWith(api), io)
    private val account = Account(fake.url, fake.me, fake.tz, api, outbox, Done(outbox, api))
    private val store by lazy { NotifyStore(PreferenceDataStoreFactory.create(scope = io) { File(tmp.root, "notify.preferences_pb") }) }
    private var clock = Instant.parse("2026-11-15T09:00:00Z")
    private val notifier = Notifier(context)
    private val scheduler by lazy { NotifyScheduler({ WorkManager.getInstance(context) }, store) { clock } }
    private val plan get() = NotifyPlan(account.key, listOf("14:00", "19:00"), "Europe/Zurich")
    private val checker by lazy { NotifyCheck({ account }, {}, store, notifier, { "en" }, { ZoneId.of("Europe/Zurich") }, { clock }) }
    private val job = object : NotifyJob {
        override suspend fun check() = checker.run()
        override suspend fun scheduleAfter(ran: Instant) = scheduler.next(plan, ran)
    }
    private val savedZone = TimeZone.getDefault()

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.createChannels("en")
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(NotifyWorkerFactory { job }).build(),
        )
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedZone)
        io.cancel()
        fake.close()
    }

    private fun infos(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(NotifyScheduler.NAME).get()
    private fun waiting(): List<WorkInfo> = infos().filter { it.state == WorkInfo.State.ENQUEUED }
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /** Runs the waiting check; returns the next waiting one. */
    private fun runWaiting(): WorkInfo {
        val id = waiting().single().id
        driver.setAllConstraintsMet(id)
        driver.setInitialDelayMet(id)
        waitUntil("next check queued") { waiting().any { it.id != id } || infos().any { it.id == id && it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } }
        return waiting().single()
    }

    @Test fun oneCheckWaitsForTheNextServerTimeWithANetwork() = runBlocking {
        scheduler.apply(plan)
        val w = waiting().single()
        assertEquals(4 * 3600_000L, w.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, w.constraints.requiredNetworkType)
    }

    @Test fun theSamePlanKeepsTheWaitingCheckAnotherReplacesIt() = runBlocking {
        scheduler.apply(plan)
        val first = waiting().single().id
        clock = clock.plusSeconds(60)
        scheduler.apply(plan)
        assertEquals(first, waiting().single().id)
        scheduler.apply(plan.copy(times = listOf("08:00")))
        val w = waiting().single()
        assertNotEquals(first, w.id)
        assertEquals(Instant.parse("2026-11-16T07:00:00Z").toEpochMilli() - clock.toEpochMilli(), w.initialDelayMillis)
    }

    @Test fun signedOutOrSwitchedOffCancels() = runBlocking {
        scheduler.apply(plan)
        scheduler.apply(null)
        assertTrue(waiting().isEmpty())
        scheduler.apply(plan.copy(times = emptyList()))
        assertTrue(waiting().isEmpty())
    }

    @Test fun theCheckShowsNotificationsAndQueuesTheNextTime() = runBlocking {
        fake.dueChores += listOf(1L, 2L)
        scheduler.apply(plan)
        clock = Instant.parse("2026-11-15T13:00:05Z")
        val next = runWaiting()
        assertEquals(Instant.parse("2026-11-15T18:00:00Z").toEpochMilli() - clock.toEpochMilli(), next.initialDelayMillis)
        val n = nm.activeNotifications.single()
        assertEquals("2 chores are due", n.notification.extras.getString("android.title"))
        assertEquals("Clean shower, Clean toilet", n.notification.extras.getCharSequence("android.text").toString())
        assertEquals(Notifier.CHANNEL_DUE, n.notification.channelId)
        val tap = n.notification.contentIntent
        assertTrue(tap.isImmutable)
        val intent = shadowOf(tap).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(1L, Notifier.household(intent, account.key) { it == 1L })
    }

    @Test fun aRunThatStartsLateStillQueuesTheNextTimeAfterNow() = runBlocking {
        scheduler.apply(plan)
        clock = Instant.parse("2026-11-15T20:00:00Z")
        val next = runWaiting()
        assertEquals(Instant.parse("2026-11-16T13:00:00Z").toEpochMilli() - clock.toEpochMilli(), next.initialDelayMillis)
    }

    @Test fun offlineRetriesTheSameCheck() = runBlocking {
        fake.down = true
        scheduler.apply(plan)
        val id = waiting().single().id
        driver.setAllConstraintsMet(id)
        driver.setInitialDelayMet(id)
        waitUntil("retry") { infos().single().runAttemptCount == 1 && infos().single().state == WorkInfo.State.ENQUEUED }
        assertEquals(id, infos().single().id)
    }

    @Test fun aTapOnlyOpensAKnownHouseholdOfTheSignedInAccount() = runBlocking {
        val pi = Notifier.openIntent(context, account.key, 2, 7)
        val intent = shadowOf(pi).savedIntent
        val known: suspend (Long) -> Boolean = { it == 2L }
        assertEquals(2L, Notifier.household(intent, account.key, known))
        assertNull(Notifier.household(intent, account.key) { false })
        assertNull(Notifier.household(intent, "https://other.example.com#1", known))
        assertNull(Notifier.household(intent, null, known))
        assertNull(Notifier.household(Intent(intent).setAction("android.intent.action.MAIN"), account.key, known))
        assertNull(Notifier.household(null, account.key, known))
    }

    @Test fun retriesUsedUpStillQueueTheNextCheck() = runBlocking {
        fake.down = true
        clock = Instant.parse("2026-11-15T13:20:00Z")
        val worker = TestListenableWorkerBuilder.from(context, NotifyWorker::class.java)
            .setWorkerFactory(NotifyWorkerFactory { job })
            .setRunAttemptCount(NotifyWorker.MAX_RETRIES)
            .setInputData(workDataOf(NotifyWorker.KEY_AT to Instant.parse("2026-11-15T13:00:00Z").toEpochMilli()))
            .build() as NotifyWorker
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(Instant.parse("2026-11-15T18:00:00Z").toEpochMilli() - clock.toEpochMilli(), waiting().single().initialDelayMillis)
    }

    @Test fun anEarlierRetryIsRetried() = runBlocking {
        fake.down = true
        val worker = TestListenableWorkerBuilder.from(context, NotifyWorker::class.java)
            .setWorkerFactory(NotifyWorkerFactory { job })
            .setRunAttemptCount(1)
            .build() as NotifyWorker
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertTrue(waiting().isEmpty())
    }

}
