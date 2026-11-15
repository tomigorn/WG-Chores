package ch.tomigorn.wgchores.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = Notifier(context)
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val home = Ref(1, "WG Zürich")
    private val key = "https://chores.example.com#1"

    @Before fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.createChannels("en")
    }

    private fun shown() = nm.activeNotifications.map { it.notification.extras.getCharSequence("android.title").toString() to it.notification.extras.getCharSequence("android.text").toString() }

    @Test fun dueChoresAddUpWhileTheNotificationIsShown() {
        notifier.due(key, home, listOf("Clean toilet"), "en")
        assertEquals(listOf("Clean toilet is due" to "WG Zürich"), shown())
        notifier.due(key, home, listOf("Clean shower", "Clean toilet"), "en")
        assertEquals(listOf("2 chores are due" to "Clean toilet, Clean shower"), shown())
    }

    @Test fun aDismissedNotificationStartsFresh() {
        notifier.due(key, home, listOf("Clean toilet"), "en")
        nm.cancelAll()
        notifier.due(key, home, listOf("Clean shower"), "en")
        assertEquals(listOf("Clean shower is due" to "WG Zürich"), shown())
    }

    @Test fun doneByOthersAppendsAndKeepsTheFirstSince() {
        notifier.done(key, home, "14:00", listOf(DoneItem("Sam", "Wipe stove", "2026-11-15")), "en")
        notifier.done(key, home, "19:00", listOf(DoneItem("Sam", "Wipe stove", "2026-11-15"), DoneItem("Lena Example", "Clean fridge", "2026-11-15")), "en")
        assertEquals(listOf("WG Zürich" to "Since 14:00: Sam ✓ Wipe stove, Lena Example ✓ Clean fridge"), shown())
    }

    @Test fun householdsStaySeparate() {
        notifier.due(key, home, listOf("Clean toilet"), "en")
        notifier.due(key, Ref(2, "Family"), listOf("Water plants"), "en")
        assertEquals(2, nm.activeNotifications.size)
    }

    @Test fun germanTextsAndChannelNames() {
        notifier.createChannels("de")
        assertEquals("Fällige Aufgaben", nm.getNotificationChannel(Notifier.CHANNEL_DUE).name)
        assertEquals("Von anderen erledigt", nm.getNotificationChannel(Notifier.CHANNEL_DONE).name)
        notifier.due(key, home, listOf("Clean toilet", "Clean shower"), "de")
        assertEquals(listOf("2 Aufgaben sind fällig" to "Clean toilet, Clean shower"), shown())
        notifier.createChannels("en")
        assertEquals("Chores due", nm.getNotificationChannel(Notifier.CHANNEL_DUE).name)
    }
}
