package ch.tomigorn.wgchores.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file by lazy { File(tmp.root, "session.preferences_pb") }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store by lazy { SessionStore(PreferenceDataStoreFactory.create(scope = scope) { file }, TokenCipher { key }) }
    private val config = ServerConfig(name = "WG Chores", localAccounts = true, openRegistration = false, minAppVersion = "1.0.0")

    @After fun stop() = scope.cancel()

    @Test fun startsEmpty() = runBlocking { assertEquals(Session(), store.read()) }

    @Test fun remembersServerConfigAndSignIn() = runBlocking {
        store.saveServer("https://chores.example.com", config)
        store.saveSignIn("tok-1", "Jane Doe")
        assertEquals(Session("https://chores.example.com", config, "tok-1", "Jane Doe"), store.read())
    }

    @Test fun tokenIsNotStoredInPlainText() = runBlocking {
        store.saveServer("https://chores.example.com", config)
        store.saveSignIn("tok-secret-1", "Jane Doe")
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("tok-secret-1"))
    }

    @Test fun signOutKeepsTheServer() = runBlocking {
        store.saveServer("https://chores.example.com", config)
        store.saveSignIn("tok-1", "Jane Doe")
        store.clearSignIn()
        assertEquals(Session("https://chores.example.com", config), store.read())
    }

    @Test fun clearAllForgetsEverything() = runBlocking {
        store.saveServer("https://chores.example.com", config)
        store.saveSignIn("tok-1", "Jane Doe")
        store.saveVerifier("v".repeat(43))
        store.clearAll()
        assertEquals(Session(), store.read())
    }

    @Test fun unreadableTokenCountsAsSignedOut() = runBlocking {
        store.saveServer("https://chores.example.com", config)
        store.saveSignIn("tok-1", "Jane Doe")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        scope.cancel()
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restored = SessionStore(PreferenceDataStoreFactory.create(scope = scope2) { file }, TokenCipher { otherKey })
        assertNull(restored.read().token)
        assertEquals("https://chores.example.com", restored.read().server)
        scope2.cancel()
    }

    @Test fun tokenIsDecryptedOnceThenCached() = runBlocking {
        var keyUses = 0
        val counted = SessionStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") }, TokenCipher { keyUses++; key })
        counted.saveSignIn("tok-1", "Jane Doe")
        repeat(3) { assertEquals("tok-1", counted.read().token) }
        assertEquals(1, keyUses)
    }

    @Test fun keystoreFailureStoresNothing() = runBlocking {
        val broken = SessionStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "b.preferences_pb") }, TokenCipher { throw java.security.KeyStoreException("gone") })
        assertEquals(false, broken.saveSignIn("tok-1", "Jane Doe"))
        assertNull(broken.read().token)
    }

    @Test fun verifierIsKeptUntilTaken() = runBlocking {
        store.saveVerifier("v".repeat(43))
        assertEquals("v".repeat(43), store.takeVerifier())
        assertNull(store.takeVerifier())
    }
}
