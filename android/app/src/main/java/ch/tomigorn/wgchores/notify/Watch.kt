package ch.tomigorn.wgchores.notify

import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.data.ServerConfig
import ch.tomigorn.wgchores.data.Account
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull

/** Turns the app state into the background check's plan: Loading is ignored, an update pause keeps the cursors, a sign-out forgets them. */
class NotifyWatch(
    private val state: Flow<AuthState>,
    private val configs: Flow<ServerConfig?>,
    private val enabled: (String) -> Flow<Boolean>,
    private val signedOut: suspend () -> Unit,
    private val apply: suspend (NotifyPlan?) -> Unit,
) {
    private sealed interface Who {
        data object Out : Who
        data object Paused : Who
        data class In(val key: String) : Who
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        state
            .mapNotNull { st ->
                when (st) {
                    AuthState.Loading -> null
                    is AuthState.SignedIn -> st.userId?.let { Who.In(Account.keyOf(st.server, it)) }
                    is AuthState.UpdateRequired -> Who.Paused
                    is AuthState.Login, is AuthState.PickServer -> Who.Out
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { who ->
                when (who) {
                    is Who.In -> combine(configs, enabled(who.key)) { config, on ->
                        who to config?.takeIf { on }?.let { NotifyPlan(who.key, it.notifyTimes, it.tz) }
                    }
                    else -> flowOf(who to null)
                }
            }
            .distinctUntilChanged()
            .collect { (who, plan) ->
                if (who == Who.Out) signedOut()
                apply(plan)
            }
    }
}
