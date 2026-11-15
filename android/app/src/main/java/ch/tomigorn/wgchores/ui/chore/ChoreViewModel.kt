package ch.tomigorn.wgchores.ui.chore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.tomigorn.wgchores.WgcApp
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.data.CachedChore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The signed-in account; null while it is not known (no user id yet and offline). */
suspend fun WgcApp.account(): Account? = container.sync.account() ?: run {
    container.auth.refreshUser()
    container.sync.account()
}

class ChoreViewModel(app: Application, val choreId: Long) : AndroidViewModel(app) {
    private val c = (app as WgcApp).container
    val controller = MutableStateFlow<ChoreController?>(null)
    private val fallback = MutableStateFlow(ChoreUi(java.time.LocalDate.now()))

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<ChoreUi> = controller.flatMapLatest { it?.state ?: fallback }.stateIn(viewModelScope, SharingStarted.Eagerly, fallback.value)

    private val cache = object : ChoreCache {
        override suspend fun save(a: Account, entry: CachedChore) = c.sync.saveChore(a, entry)
        override suspend fun load(a: Account, id: Long) = c.sync.loadChore(a, id)
    }

    suspend fun load() {
        val ctl = controller.value ?: getApplication<WgcApp>().account()?.let { a ->
            ChoreController(a, choreId, cache, viewModelScope).also { controller.value = it }
        }
        if (ctl == null) fallback.update { it.copy(phase = Phase.Failed) } else ctl.load()
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    companion object {
        fun factory(app: Application, id: Long): ViewModelProvider.Factory = viewModelFactory { initializer { ChoreViewModel(app, id) } }
    }
}
