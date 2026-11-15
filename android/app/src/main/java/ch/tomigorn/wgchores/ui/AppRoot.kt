package ch.tomigorn.wgchores.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.ui.screens.LoginScreen
import ch.tomigorn.wgchores.ui.screens.ServerScreen
import ch.tomigorn.wgchores.ui.screens.UpdateScreen
import ch.tomigorn.wgchores.ui.theme.Wgc

interface AppActions {
    fun connect(url: String)
    fun login(username: String, pw: String)
    fun register(displayName: String, username: String, pw: String)
    fun sso()
    fun retry()
    fun changeServer()
    fun openStore()
    fun signOut()
    fun setTheme(mode: ThemeMode)
}

/** Signed-out screens follow the auth state; the signed-in area has its own navigation graph. */
@Composable
fun AppRoot(state: AuthState, theme: ThemeMode, actions: AppActions) {
    when (state) {
        AuthState.Loading -> Box(Modifier.fillMaxSize().background(Wgc.colors.bg))
        is AuthState.PickServer -> ServerScreen(state, actions::connect)
        is AuthState.Login -> LoginScreen(state, actions::sso, actions::login, actions::register, actions::retry, actions::changeServer)
        is AuthState.UpdateRequired -> UpdateScreen(state, actions::openStore, actions::changeServer)
        is AuthState.SignedIn -> SignedInArea(state, theme, actions)
    }
}
