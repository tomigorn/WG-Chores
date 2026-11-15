package ch.tomigorn.wgchores.notify

import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.data.ServerConfig
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyWatchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = "https://chores.example.com"
    private val key = "$server#1"
    private val state = MutableStateFlow<AuthState>(AuthState.Loading)
    private val config = MutableStateFlow<ServerConfig?>(cfg(listOf("14:00", "19:00")))
    private val on = MutableStateFlow(true)
    private val events = CopyOnWriteArrayList<String>()

    private fun cfg(times: List<String>) = ServerConfig("WG Chores", localAccounts = true, openRegistration = false, notifyTimes = times, tz = "Europe/Zurich", minAppVersion = "0.0.0")

    private val watch = NotifyWatch(state, config, { k -> check(k == key); on }, signedOut = { events += "out" }, apply = { p -> events += "apply:${p?.times}" })

    @After fun stop() = scope.cancel()

    private fun waitFor(vararg expected: String) {
        val end = System.currentTimeMillis() + 5_000
        while (events.toList() != expected.toList()) {
            check(System.currentTimeMillis() < end) { "got $events" }
            Thread.sleep(10)
        }
    }

    @Test fun followsSignInConfigSwitchesAndSignOut() {
        scope.launch { watch.run() }
        Thread.sleep(100)
        assertEquals(emptyList<String>(), events.toList())
        state.value = AuthState.SignedIn(server, "Jane Doe", null)
        Thread.sleep(100)
        assertEquals(emptyList<String>(), events.toList())
        state.value = AuthState.SignedIn(server, "Jane Doe", 1)
        waitFor("apply:[14:00, 19:00]")
        state.value = AuthState.SignedIn(server, "Jane Example", 1)
        config.value = cfg(listOf("08:00"))
        waitFor("apply:[14:00, 19:00]", "apply:[08:00]")
        on.value = false
        waitFor("apply:[14:00, 19:00]", "apply:[08:00]", "apply:null")
        on.value = true
        waitFor("apply:[14:00, 19:00]", "apply:[08:00]", "apply:null", "apply:[08:00]")
        state.value = AuthState.Login(server, null)
        waitFor("apply:[14:00, 19:00]", "apply:[08:00]", "apply:null", "apply:[08:00]", "out", "apply:null")
    }

    @Test fun updateRequiredPausesWithoutForgetting() {
        scope.launch { watch.run() }
        state.value = AuthState.SignedIn(server, "Jane Doe", 1)
        waitFor("apply:[14:00, 19:00]")
        state.value = AuthState.UpdateRequired(server, "9.0.0", "1.0.0")
        waitFor("apply:[14:00, 19:00]", "apply:null")
        state.value = AuthState.SignedIn(server, "Jane Doe", 1)
        waitFor("apply:[14:00, 19:00]", "apply:null", "apply:[14:00, 19:00]")
    }
}
