package ch.tomigorn.wgchores.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }) }

    @After fun stop() = scope.cancel()

    @Test fun themeFollowsTheSystemByDefault() = runBlocking { assertEquals(ThemeMode.System, store.theme.first()) }

    @Test fun remembersTheTheme() = runBlocking {
        store.setTheme(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, store.theme.first())
    }
}
