package ch.tomigorn.wgchores.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import ch.tomigorn.wgchores.auth.AuthState.Login
import ch.tomigorn.wgchores.auth.AuthState.PickServer
import ch.tomigorn.wgchores.auth.AuthState.SignedIn
import ch.tomigorn.wgchores.auth.AuthState.UpdateRequired
import ch.tomigorn.wgchores.data.SessionStore
import ch.tomigorn.wgchores.data.TokenCipher
import ch.tomigorn.wgchores.testing.FakeServer
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fake = FakeServer().start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store by lazy {
        SessionStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") }, TokenCipher { key })
    }
    private val auth by lazy { AuthController(store, appVersion = "1.0.0", deviceName = "Pixel 9", allowLocalHttp = true) }

    @After fun stop() {
        fake.close()
        scope.cancel()
    }

    private fun signedInViaForm() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        auth.login("anna", fake.pw)
    }

    @Test fun firstStartAsksForTheServer() = runBlocking {
        auth.start()
        assertEquals(PickServer(), auth.state.value)
    }

    @Test fun connectShowsTheServersLoginOptions() = runBlocking {
        auth.start()
        auth.connect(fake.url + "/")
        val s = auth.state.value as Login
        assertEquals(fake.url, s.server)
        assertEquals("Holy Grail", s.config?.oidc?.name)
        assertEquals(fake.url, store.read().server)
    }

    @Test fun connectRefusesPlainHttp() = runBlocking {
        auth.start()
        auth.connect("http://chores.example.com")
        assertEquals(PickServer("http://chores.example.com", ServerError.NotHttps), auth.state.value)
        assertNull(store.read().server)
    }

    @Test fun connectRefusesAnotherApp() = runBlocking {
        fake.appName = "Nextcloud"
        auth.start()
        auth.connect(fake.url)
        assertEquals(ServerError.NotWgChores, (auth.state.value as PickServer).error)
    }

    @Test fun connectToNothingIsUnreachable() = runBlocking {
        val url = fake.url
        fake.close()
        auth.start()
        auth.connect(url)
        assertEquals(ServerError.Unreachable, (auth.state.value as PickServer).error)
    }

    @Test fun tooOldAppMustUpdate() = runBlocking {
        fake.minAppVersion = "2.0.0"
        auth.start()
        auth.connect(fake.url)
        assertEquals(UpdateRequired(fake.url, "2.0.0", "1.0.0"), auth.state.value)
    }

    @Test fun passwordLoginSignsIn() {
        signedInViaForm()
        assertEquals(SignedIn(fake.url, "Jane Doe"), auth.state.value)
        runBlocking { assertTrue(store.read().token in fake.issued) }
    }

    @Test fun wrongPasswordStaysOnLoginWithTheServerError() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        auth.login("anna", "not the password")
        assertEquals(LoginError.Code("invalid_credentials"), (auth.state.value as Login).error)
    }

    @Test fun registerSignsIn() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        auth.register("Jane Doe", "anna", fake.pw)
        assertEquals(SignedIn(fake.url, "Jane Doe"), auth.state.value)
    }

    @Test fun restartStaysSignedInWithoutNetwork() {
        signedInViaForm()
        fake.close()
        val again = AuthController(store, appVersion = "1.0.0", deviceName = "Pixel 9", allowLocalHttp = true)
        runBlocking { again.start() }
        assertEquals(SignedIn(fake.url, "Jane Doe"), again.state.value)
    }

    @Test fun restartWithAServerShowsItsLogin() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        val again = AuthController(store, appVersion = "1.0.0", deviceName = "Pixel 9", allowLocalHttp = true)
        again.start()
        assertEquals(fake.url, (again.state.value as Login).server)
    }

    @Test fun signOutTellsTheServerAndKeepsTheServer() {
        signedInViaForm()
        runBlocking { auth.signOut() }
        assertTrue(fake.issued.isEmpty())
        assertEquals(fake.url, (auth.state.value as Login).server)
        runBlocking { assertNull(store.read().token) }
    }

    @Test fun signOutOfflineStillForgetsTheToken() {
        signedInViaForm()
        fake.close()
        runBlocking { auth.signOut() }
        assertTrue(auth.state.value is Login)
        runBlocking { assertNull(store.read().token) }
    }

    @Test fun revokedTokenGoesToLoginWithTheServerKept() {
        signedInViaForm()
        fake.issued.clear()
        runBlocking { auth.refreshUser() }
        assertEquals(fake.url, (auth.state.value as Login).server)
        assertNull((auth.state.value as Login).error)
        runBlocking { assertNull(store.read().token) }
    }

    @Test fun changeServerWipesEverything() {
        signedInViaForm()
        runBlocking { auth.changeServer() }
        assertEquals(PickServer(), auth.state.value)
        runBlocking { assertNull(store.read().server) }
    }

    @Test fun ssoRoundTripSignsIn() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        val start = auth.beginSso()!!
        val challenge = start.substringAfter("challenge=")
        auth.finishSso("wgchores://auth?code=${fake.issueSsoCode(challenge)}")
        assertEquals(SignedIn(fake.url, "Jane Doe"), auth.state.value)
    }

    @Test fun ssoErrorFromTheServerIsShown() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        auth.beginSso()
        auth.finishSso("wgchores://auth?error=not_allowed")
        assertEquals(LoginError.Code("not_allowed"), (auth.state.value as Login).error)
    }

    @Test fun ssoCodeForAnotherChallengeFailsAndBurnsTheVerifier() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        auth.beginSso()
        auth.finishSso("wgchores://auth?code=${fake.issueSsoCode("someone-elses-challenge")}")
        assertEquals(LoginError.Code("invalid_code"), (auth.state.value as Login).error)
        assertNull(store.takeVerifier())
    }

    @Test fun cachedMinimumVersionAppliesOfflineWhenSignedIn() {
        signedInViaForm()
        runBlocking { store.saveConfig(store.read().config!!.copy(minAppVersion = "2.0.0")) }
        fake.close()
        val again = AuthController(store, appVersion = "1.0.0", deviceName = "Pixel 9", allowLocalHttp = true)
        runBlocking { again.start() }
        assertEquals(UpdateRequired(fake.url, "2.0.0", "1.0.0"), again.state.value)
    }

    @Test fun cachedMinimumVersionAppliesOfflineBeforeSignIn() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        store.saveConfig(store.read().config!!.copy(minAppVersion = "2.0.0"))
        fake.close()
        val again = AuthController(store, appVersion = "1.0.0", deviceName = "Pixel 9", allowLocalHttp = true)
        again.start()
        assertTrue(again.state.value is UpdateRequired)
    }

    @Test fun keystoreFailureOnSignInIsACalmError() = runBlocking {
        val broken = SessionStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "b.preferences_pb") },
            TokenCipher { throw java.security.KeyStoreException("gone") },
        )
        val a = AuthController(broken, appVersion = "1.0.0", deviceName = "Pixel 9", allowLocalHttp = true)
        a.start()
        a.connect(fake.url)
        a.login("anna", fake.pw)
        assertEquals(LoginError.Code("keystore_failed"), (a.state.value as Login).error)
    }

    @Test fun unexpectedFailureEndsBusyWithTheGenericError() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        auth.failed()
        val s = auth.state.value as Login
        assertEquals(LoginError.Code("unexpected"), s.error)
        assertEquals(false, s.busy)
    }

    @Test fun unexpectedFailureOnTheServerPicker() = runBlocking {
        auth.start()
        auth.failed()
        assertEquals(ServerError.Unexpected, (auth.state.value as PickServer).error)
    }

    @Test fun ssoCallbackWithoutAPendingStartIsRefused() = runBlocking {
        auth.start()
        auth.connect(fake.url)
        auth.finishSso("wgchores://auth?code=${fake.issueSsoCode("x")}")
        assertEquals(LoginError.Code("sso_failed"), (auth.state.value as Login).error)
    }
}
