package ch.tomigorn.wgchores.ui.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/** The two notification switches: one request at a time, a failure goes back to what the server last confirmed. */
class NotifySync(private val patch: suspend (done: Boolean, on: Boolean) -> State) {
    data class State(val done: Boolean, val due: Boolean)

    private val _state = MutableStateFlow(State(done = false, due = false))
    /** What the switches show: the change being sent, else the server's last answer. */
    val state: StateFlow<State> = _state
    private val lock = Mutex()
    @Volatile private var server = State(done = false, due = false)

    fun confirmed(done: Boolean, due: Boolean) {
        server = State(done, due)
        _state.value = server
    }

    /** False when the server did not take it; the switches then show the last confirmed state again. */
    suspend fun set(done: Boolean, on: Boolean): Boolean {
        _state.update { if (done) it.copy(done = on) else it.copy(due = on) }
        return lock.withLock {
            try {
                server = patch(done, on)
                _state.value = server
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = server
                false
            }
        }
    }
}
