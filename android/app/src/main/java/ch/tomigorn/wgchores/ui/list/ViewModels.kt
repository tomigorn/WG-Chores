package ch.tomigorn.wgchores.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.WgcApp
import ch.tomigorn.wgchores.api.ApiException
import ch.tomigorn.wgchores.api.OfflineException
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.data.CachedList
import ch.tomigorn.wgchores.ui.screens.HouseholdsUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Start {
    data class Household(val id: Long) : Start
    data object Households : Start
}

/** The signed-in area: where to start, the household list, the waiting count. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as WgcApp).container
    val start = MutableStateFlow<Start?>(null)
    val households = MutableStateFlow(HouseholdsUi())
    /** Scroll position of the households list: its screen is rebuilt each time it is opened (household → list wipes the back stack). */
    var householdsScroll = 0
    private val account = MutableStateFlow<Account?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val pending: StateFlow<Int> = account.flatMapLatest { it?.outbox?.count ?: flowOf(0) }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        viewModelScope.launch {
            val user = c.auth.refreshUser()
            val s = c.session.read()
            val last = if (user != null) user.lastHouseholdId else s.lastHousehold
            account.value = c.sync.account()
            account.value?.outbox?.refreshCount()
            start.value = last?.let { Start.Household(it) } ?: Start.Households
            c.sync.kick()
        }
    }

    fun loadHouseholds() {
        viewModelScope.launch {
            val a = account.value ?: c.sync.account().also { account.value = it } ?: return@launch households.update { it.copy(failed = true) }
            try {
                val list = a.api.households()
                households.update { it.copy(households = list, loaded = true, offline = false, failed = false) }
            } catch (e: OfflineException) {
                households.update { it.copy(offline = true, failed = !it.loaded) }
            } catch (e: ApiException) {
                households.update { it.copy(failed = !it.loaded) }
            }
        }
    }

    fun joinLink(v: String) = households.update { it.copy(join = if (v.isEmpty()) ch.tomigorn.wgchores.ui.screens.JoinUi() else it.join.copy(link = v, error = null, errorArg = null)) }

    private fun joinError(id: Int, arg: String? = null) = households.update { it.copy(join = it.join.copy(busy = false, error = id, errorArg = arg)) }

    fun showInvite() {
        val a = account.value ?: return
        val link = households.value.join.link
        val token = when (val r = ch.tomigorn.wgchores.domain.InviteLink.parse(link, a.server)) {
            is ch.tomigorn.wgchores.domain.InviteLink.Ok -> r.token
            is ch.tomigorn.wgchores.domain.InviteLink.OtherServer -> return joinError(R.string.join_other_server, r.host)
            ch.tomigorn.wgchores.domain.InviteLink.Invalid -> return joinError(R.string.join_invalid)
        }
        households.update { it.copy(join = it.join.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                val p = a.api.invitePreview(token)
                val reason = if (p.valid) null else when (p.reason) {
                    "used" -> R.string.error_invite_used
                    "expired" -> R.string.error_invite_expired
                    "cancelled" -> R.string.error_invite_cancelled
                    else -> R.string.error_invite_not_found
                }
                households.update { it.copy(join = it.join.copy(busy = false, token = token, preview = p, error = reason, now = java.time.Instant.now())) }
            } catch (e: OfflineException) {
                joinError(R.string.error_offline)
            } catch (e: ApiException) {
                joinError(if (e.status == 404) R.string.error_invite_not_found else ch.tomigorn.wgchores.ui.ErrorText.of(e))
            }
        }
    }

    /** [opened] gets the household id and whether the user was a member already. */
    fun join(opened: (Long, String, Boolean) -> Unit) {
        val a = account.value ?: return
        val j = households.value.join
        val token = j.token ?: return // leak-check: ok
        val name = j.preview?.householdName.orEmpty()
        households.update { it.copy(join = it.join.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                val r = a.api.acceptInvite(token)
                households.update { it.copy(join = ch.tomigorn.wgchores.ui.screens.JoinUi()) }
                opened(r.householdId, name, r.alreadyMember)
            } catch (e: OfflineException) {
                joinError(R.string.error_offline)
            } catch (e: ApiException) {
                joinError(ch.tomigorn.wgchores.ui.ErrorText.of(e))
            }
        }
    }

    fun create(name: String, opened: (Long) -> Unit) {
        val a = account.value ?: return
        households.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val h = a.api.createHousehold(name)
                households.update { it.copy(busy = false, households = it.households + h) }
                opened(h.id)
            } catch (e: OfflineException) {
                households.update { it.copy(busy = false, error = R.string.done_connect) }
            } catch (e: ApiException) {
                households.update { it.copy(busy = false, error = if (e.code == "invalid_name") R.string.error_invalid_name else R.string.error_generic) }
            }
        }
    }
}

/** One household's chore list; the controller exists once the signed-in account is known. */
class HouseholdViewModel(app: Application, val householdId: Long) : AndroidViewModel(app) {
    private val c = (app as WgcApp).container
    val controller = MutableStateFlow<ListController?>(null)

    /** True while the list is on screen (resumed). */
    var active = false
        set(v) {
            field = v
            controller.value?.active = v
        }

    private val fallback = MutableStateFlow(ListUi(java.time.LocalDate.now()))

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<ListUi> = controller.flatMapLatest { it?.state ?: fallback }.stateIn(viewModelScope, SharingStarted.Eagerly, fallback.value)

    private val cache = object : ListCache {
        override suspend fun save(a: Account, list: CachedList) = c.sync.saveList(a, list)
        override suspend fun load(a: Account, hid: Long) = c.sync.loadList(a, hid)
    }

    private suspend fun ensure(): ListController? {
        controller.value?.let { return it }
        val a = c.sync.account() ?: run {
            c.auth.refreshUser()
            c.sync.account()
        } ?: return null
        return ListController(a, householdId, cache, viewModelScope, onOpened = ::opened).also {
            it.active = active
            controller.value = it
        }
    }

    private suspend fun opened(id: Long) {
        c.session.saveLastHousehold(id)
        controller.value?.let { runCatching { c.sync.account()?.api?.setLastHousehold(id) } }
    }

    suspend fun load() {
        val ctl = ensure()
        if (ctl == null) {
            fallback.update { it.copy(failed = true) }
            return
        }
        ctl.load()
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    companion object {
        fun factory(app: Application, id: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { HouseholdViewModel(app, id) }
        }
    }
}
