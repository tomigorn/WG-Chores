package ch.tomigorn.wgchores.auth

import ch.tomigorn.wgchores.api.ApiClient
import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.AuthResult
import ch.tomigorn.wgchores.api.NotWgChoresException
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.api.User
import ch.tomigorn.wgchores.data.AppVersion
import ch.tomigorn.wgchores.data.ServerConfig
import ch.tomigorn.wgchores.data.ServerUrl
import ch.tomigorn.wgchores.data.ServerUrlResult
import ch.tomigorn.wgchores.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Server choice, sign-in and sign-out; the single source of the app's auth state. */
class AuthController(
    private val store: SessionStore,
    private val appVersion: String,
    private val deviceName: String,
    private val allowLocalHttp: Boolean,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    fun api(server: String): ApiClient =
        ApiClient(server, bearer = { store.read().token }, onUnauthenticated = { toLogin(server) })

    // start() and an SSO callback can arrive together on a cold start; one must not overwrite the other
    private val transitions = Mutex()

    suspend fun start() = transitions.withLock { startLocked() }

    private suspend fun startLocked() {
        val s = store.read()
        val server = s.server ?: return set(AuthState.PickServer())
        if (s.config != null && tooOld(server, s.config)) return
        if (s.token != null) {
            set(AuthState.SignedIn(server, s.userName.orEmpty(), s.userId))
            refreshConfig(server)
        } else {
            set(AuthState.Login(server, s.config))
            refreshConfig(server)
        }
    }

    /** Fetches the config again; offline keeps what is shown. */
    suspend fun refreshConfig(server: String) {
        val config = try {
            ApiClient.probe(server)
        } catch (e: OfflineException) {
            _state.update { if (it is AuthState.Login && it.config == null) it.copy(error = LoginError.Offline) else it }
            return
        } catch (e: NotWgChoresException) {
            return
        }
        store.saveConfig(config)
        if (tooOld(server, config)) return
        _state.update { if (it is AuthState.Login && it.server == server) it.copy(config = config, error = null) else it }
    }

    suspend fun connect(input: String) {
        val url = when (val r = ServerUrl.normalize(input, allowLocalHttp)) {
            is ServerUrlResult.Ok -> r.url
            ServerUrlResult.Empty, ServerUrlResult.Invalid -> return set(AuthState.PickServer(input, ServerError.Invalid))
            ServerUrlResult.NotHttps -> return set(AuthState.PickServer(input, ServerError.NotHttps))
        }
        set(AuthState.PickServer(input, busy = true))
        val config = try {
            ApiClient.probe(url)
        } catch (e: OfflineException) {
            return set(AuthState.PickServer(input, ServerError.Unreachable))
        } catch (e: NotWgChoresException) {
            return set(AuthState.PickServer(input, ServerError.NotWgChores))
        }
        store.saveServer(url, config)
        if (!tooOld(url, config)) set(AuthState.Login(url, config))
    }

    suspend fun login(username: String, pw: String) = signIn { api(it).login(username, pw, deviceName) }

    suspend fun register(displayName: String, username: String, pw: String) =
        signIn { api(it).register(username, pw, displayName, deviceName) }

    /** Returns the URL to open in the Auth Tab, or null when no server is chosen. */
    suspend fun beginSso(): String? {
        val server = store.read().server ?: return null
        val verifier = Pkce.newVerifier()
        if (!store.saveVerifier(verifier)) {
            loginError(LoginError.Code("keystore_failed"))
            return null
        }
        _state.update { if (it is AuthState.Login) it.copy(error = null) else it }
        return SsoCallback.startUrl(server, Pkce.challenge(verifier))
    }

    /** A callback counts only while signed out and with a sign-in started here; anything else is ignored. */
    suspend fun finishSso(uri: String) = transitions.withLock { finishSsoLocked(uri) }

    private suspend fun finishSsoLocked(uri: String) {
        if (store.read().token != null) return
        val cb = SsoCallback.parse(uri) ?: return
        val verifier = store.takeVerifier() ?: return
        when (cb) {
            is SsoCallback.Error -> loginError(LoginError.Code(cb.code))
            is SsoCallback.Code -> signIn { server ->
                val bearer = api(server).appToken(cb.code, verifier, deviceName)
                AuthResult(bearer, ApiClient(server, bearer = { bearer }).me())
            }
        }
    }

    /** Checks the token with the server (a revoked one leads to the login screen); null when offline or refused. */
    suspend fun refreshUser(): User? {
        val s = store.read()
        val server = s.server ?: return null
        if (s.token == null) return null
        return try {
            val user = api(server).me()
            store.saveUser(user.id, user.displayName, user.lastHouseholdId)
            _state.update { if (it is AuthState.SignedIn) it.copy(userName = user.displayName, userId = user.id) else it }
            user
        } catch (_: ApiException) {
            null
        } catch (_: OfflineException) {
            null
        }
    }

    /** Something unexpected went wrong in an action: end any busy state and show the generic error. */
    fun failed() {
        _state.update {
            when (it) {
                is AuthState.Login -> it.copy(busy = false, error = LoginError.Code("unexpected"))
                is AuthState.PickServer -> it.copy(busy = false, error = ServerError.Unexpected)
                AuthState.Loading -> AuthState.PickServer(error = ServerError.Unexpected)
                else -> it
            }
        }
    }

    suspend fun signOut() {
        val s = store.read()
        val server = s.server ?: return set(AuthState.PickServer())
        if (s.token != null) {
            try {
                api(server).logout()
            } catch (_: ApiException) {
            } catch (_: OfflineException) {
            }
        }
        toLogin(server)
    }

    suspend fun changeServer() {
        val s = store.read()
        if (s.server != null && s.token != null) {
            try {
                api(s.server).logout()
            } catch (_: ApiException) {
            } catch (_: OfflineException) {
            }
        }
        store.clearAll()
        set(AuthState.PickServer())
    }

    /** The account is gone on the server: forget the server and the session, back to the server picker. */
    suspend fun accountDeleted() {
        store.clearAll()
        set(AuthState.PickServer())
    }

    private suspend fun toLogin(server: String) {
        store.clearSignIn()
        set(AuthState.Login(server, store.read().config))
    }

    private suspend fun signIn(call: suspend (String) -> AuthResult) {
        val server = store.read().server ?: return set(AuthState.PickServer())
        _state.update { if (it is AuthState.Login) it.copy(busy = true, error = null) else it }
        val result = try {
            call(server)
        } catch (e: ApiException) {
            return loginError(LoginError.Code(e.code))
        } catch (e: OfflineException) {
            return loginError(LoginError.Offline)
        }
        val name = result.user?.displayName.orEmpty()
        if (!store.saveSignIn(result.token, name, result.user?.id)) {
            try {
                ApiClient(server, bearer = { result.token }).logout()
            } catch (_: ApiException) {
            } catch (_: OfflineException) {
            }
            return loginError(LoginError.Code("keystore_failed"))
        }
        set(AuthState.SignedIn(server, name, result.user?.id))
    }

    private suspend fun loginError(error: LoginError) {
        val s = store.read()
        val server = s.server ?: return set(AuthState.PickServer())
        set(AuthState.Login(server, s.config, error = error))
    }

    private fun tooOld(server: String, config: ServerConfig): Boolean {
        if (!AppVersion.isOlder(appVersion, config.minAppVersion)) return false
        set(AuthState.UpdateRequired(server, config.minAppVersion, appVersion))
        return true
    }

    private fun set(s: AuthState) {
        _state.value = s
    }
}
