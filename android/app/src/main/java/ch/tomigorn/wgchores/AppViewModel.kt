package ch.tomigorn.wgchores

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.data.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as WgcApp).container
    val auth: StateFlow<AuthState> = c.auth.state
    val theme: StateFlow<ThemeMode> = c.settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)

    init {
        act { c.auth.start() }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WgChores", "action failed", e)
                c.auth.failed()
            }
        }
    }

    fun connect(url: String) = act { c.auth.connect(url) }
    fun login(username: String, pw: String) = act { c.auth.login(username, pw) }
    fun register(displayName: String, username: String, pw: String) = act { c.auth.register(displayName, username, pw) }
    fun retry() = act { c.auth.start() }
    /** Sign-out drops the account's waiting marks (the menu warns first) and its cached lists. */
    fun signOut() = act {
        c.sync.account()?.let { c.sync.forget(it) }
        c.auth.signOut()
    }
    fun changeServer() = act { c.auth.changeServer() }
    fun finishSso(uri: String) = act { c.auth.finishSso(uri) }
    fun setTheme(mode: ThemeMode) = act { c.settings.setTheme(mode) }

    fun beginSso(open: (String) -> Unit) = act { c.auth.beginSso()?.let(open) }
}
