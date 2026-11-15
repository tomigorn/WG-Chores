package ch.tomigorn.wgchores.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.ui.components.BarActions
import ch.tomigorn.wgchores.ui.components.Dest
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.list.HomeViewModel
import ch.tomigorn.wgchores.ui.list.HouseholdViewModel
import ch.tomigorn.wgchores.ui.list.Notice
import ch.tomigorn.wgchores.ui.list.Start
import ch.tomigorn.wgchores.ui.screens.HouseholdItem
import ch.tomigorn.wgchores.ui.screens.HouseholdScreen
import ch.tomigorn.wgchores.ui.screens.HouseholdsScreen
import ch.tomigorn.wgchores.ui.screens.ListActions
import ch.tomigorn.wgchores.domain.Device
import ch.tomigorn.wgchores.ui.account.AccountActions
import ch.tomigorn.wgchores.ui.account.AccountScreen
import ch.tomigorn.wgchores.ui.account.AccountViewModel
import ch.tomigorn.wgchores.ui.account.SettingsActions
import ch.tomigorn.wgchores.ui.account.SettingsScreen
import ch.tomigorn.wgchores.ui.account.SettingsViewModel
import ch.tomigorn.wgchores.ui.chore.ChoreActions
import ch.tomigorn.wgchores.ui.chore.ChoreEditScreen
import ch.tomigorn.wgchores.ui.chore.ChoreEditViewModel
import ch.tomigorn.wgchores.ui.chore.ChoreScreen
import ch.tomigorn.wgchores.ui.chore.ChoreViewModel
import ch.tomigorn.wgchores.ui.chore.EditActions
import ch.tomigorn.wgchores.ui.chore.EditDone
import ch.tomigorn.wgchores.ui.chore.Entry
import ch.tomigorn.wgchores.ui.components.ToastHost
import ch.tomigorn.wgchores.ui.household.HhActions
import ch.tomigorn.wgchores.ui.household.HouseholdSettingsScreen
import ch.tomigorn.wgchores.ui.household.HouseholdSettingsViewModel
import ch.tomigorn.wgchores.ui.household.copyText
import ch.tomigorn.wgchores.ui.household.shareInvite
import ch.tomigorn.wgchores.ui.list.noticeText
import ch.tomigorn.wgchores.ui.screens.JoinActions
import androidx.compose.ui.Alignment
import ch.tomigorn.wgchores.ui.screens.SheetHandle
import ch.tomigorn.wgchores.ui.theme.Wgc
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable data class HouseholdRoute(val id: Long)
@Serializable data class HouseholdsRoute(val create: Boolean = false)
@Serializable data class ChoreRoute(val id: Long)
/** [choreId] 0 = a new chore in [householdId]. */
@Serializable data class EditRoute(val choreId: Long = 0, val householdId: Long = 0)
@Serializable data class HouseholdSettingsRoute(val id: Long)
@Serializable data object AccountRoute
@Serializable data object SettingsRoute

/** Short texts that outlive a screen ("Clean toilet added" after going back to the list). */
class AppToast {
    var notice by mutableStateOf<Notice?>(null)
        private set
    var seq by mutableStateOf(0L)
        private set

    fun show(n: Notice) {
        notice = n
        seq++
    }

    fun dismiss(s: Long) {
        if (s == seq) notice = null
    }
}

/** Signed in: the last household (else the household list), chore page, editing, household settings, account, settings. */
@Composable
fun SignedInArea(state: AuthState.SignedIn, theme: ThemeMode, actions: AppActions) {
    val home: HomeViewModel = viewModel(key = "home|${state.server}|${state.userId}")
    val start by home.start.collectAsStateWithLifecycle()
    val pending by home.pending.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    val s = start ?: return Box(Modifier.fillMaxSize().background(Wgc.colors.bg))
    val nav = rememberNavController()
    val toast = remember { AppToast() }
    val signOut = { if (pending > 0) confirmSignOut = true else actions.signOut() }

    fun bar(onStatus: (Boolean) -> Unit = {}, onSwitch: () -> Unit = {}, hid: Long? = null) = object : BarActions {
        override fun switchHousehold() = onSwitch()
        override fun status(offline: Boolean) = onStatus(offline)
        override fun theme(mode: ThemeMode) = actions.setTheme(mode)
        override fun signOut() = signOut()
        override fun navigate(d: Dest) = when (d) {
            Dest.Households -> nav.navigate(HouseholdsRoute())
            Dest.NewHousehold -> nav.navigate(HouseholdsRoute(create = true))
            Dest.HouseholdSettings -> if (hid != null) nav.navigate(HouseholdSettingsRoute(hid)) else Unit
            Dest.Settings -> nav.navigate(SettingsRoute)
            Dest.Account -> nav.navigate(AccountRoute)
        }
    }
    val back: () -> Unit = { if (!nav.popBackStack()) nav.navigate(HouseholdsRoute()) }

    val startRoute: Any = when (s) {
        is Start.Household -> HouseholdRoute(s.id)
        Start.Households -> HouseholdsRoute()
    }
    val app = LocalContext.current.applicationContext as Application
    Box(Modifier.fillMaxSize()) {
    NavHost(nav, startDestination = startRoute) {
        composable<HouseholdRoute> { entry ->
            HouseholdRouteContent(entry.toRoute<HouseholdRoute>().id, state.userName, theme, home, nav, ::bar)
        }
        composable<HouseholdsRoute> { entry ->
            val ui by home.households.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { home.loadHouseholds() }
            val join = object : JoinActions {
                override fun link(v: String) = home.joinLink(v)
                override fun show() = home.showInvite()
                override fun join() = home.join { id, name, already ->
                    toast.show(Notice.Res(if (already) R.string.invite_already else R.string.invite_joined, name))
                    nav.openHousehold(id)
                }
            }
            HouseholdsScreen(
                ui, state.userName, pending, theme, entry.toRoute<HouseholdsRoute>().create, bar(),
                onOpen = { id -> nav.openHousehold(id) },
                onCreate = { name -> home.create(name) { id -> nav.openHousehold(id) } },
                onRetry = home::loadHouseholds,
                onSettings = { id -> nav.navigate(HouseholdSettingsRoute(id)) },
                join = join,
            )
        }
        composable<ChoreRoute> { entry -> ChoreRouteContent(entry.toRoute<ChoreRoute>().id, app, nav, back) }
        composable<EditRoute> { entry ->
            val r = entry.toRoute<EditRoute>()
            val vm: ChoreEditViewModel = viewModel(factory = ChoreEditViewModel.factory(app, r.choreId.takeIf { it > 0 }, r.householdId.takeIf { it > 0 }))
            val ui by vm.ui.collectAsStateWithLifecycle()
            LaunchedEffect(ui.done) {
                when (val d = ui.done) {
                    is EditDone.Created -> {
                        toast.show(Notice.Res(R.string.edit_created, d.name))
                        back()
                    }
                    is EditDone.Saved -> {
                        toast.show(Notice.Res(R.string.edit_saved))
                        back()
                    }
                    is EditDone.Archived -> {
                        toast.show(Notice.Res(R.string.edit_archived, d.name))
                        if (!nav.popBackStack<HouseholdRoute>(inclusive = false)) nav.openHousehold(d.householdId)
                    }
                    null -> {}
                }
            }
            ChoreEditScreen(ui, object : EditActions by vm.actions {
                override fun cancel() = back()
            })
        }
        composable<HouseholdSettingsRoute> { entry ->
            val id = entry.toRoute<HouseholdSettingsRoute>().id
            val vm: HouseholdSettingsViewModel = viewModel(factory = HouseholdSettingsViewModel.factory(app, id))
            val ui by vm.ui.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val shareTitle = stringResource(R.string.hh_share_text, ui.household?.name.orEmpty(), stringResource(R.string.app_name))
            LaunchedEffect(ui.left) {
                if (ui.left) nav.navigate(HouseholdsRoute()) { popUpTo(nav.graph.id) { inclusive = true } }
            }
            HouseholdSettingsScreen(ui, object : HhActions by vm.actions {
                override fun back() = back()
                override fun share(url: String) = shareInvite(context, url, shareTitle)
                override fun copy(url: String) {
                    copyText(context, shareTitle, url)
                    vm.copied()
                }
            })
        }
        composable<AccountRoute> {
            val vm: AccountViewModel = viewModel()
            val ui by vm.ui.collectAsStateWithLifecycle()
            val context = LocalContext.current
            AccountScreen(ui, object : AccountActions {
                override fun back() = back()
                override fun retry() = vm.load()
                override fun manage(url: String) = actions.openUrl(url)
                override fun name(v: String) = vm.edit { it.copy(name = v.take(60), nameError = null) }
                override fun saveName() = vm.saveName()
                override fun current(v: String) = vm.edit { it.copy(current = v) }
                override fun pw(v: String) = vm.edit { it.copy(pw = v) }
                override fun repeat(v: String) = vm.edit { it.copy(repeat = v) }
                override fun savePassword() = vm.savePassword()
                override fun signOutDevice(d: Device) {
                    if (d.current) signOut() else vm.signOutOther(d, d.deviceName ?: context.getString(if (d.kind == "web") R.string.devices_browser else R.string.devices_android))
                }
                override fun signOut() = signOut()
                override fun askDelete() = vm.edit { it.copy(deleting = true) }
                override fun typed(v: String) = vm.edit { it.copy(typed = v, deleteError = null) }
                override fun delete() = vm.delete()
                override fun closeDelete() = vm.edit { it.copy(deleting = false, typed = "", deleteError = null) }
                override fun dismissSnack(seq: Long) = vm.edit { if (it.snackSeq == seq) it.copy(snack = null) else it }
            })
        }
        composable<SettingsRoute> {
            val vm: SettingsViewModel = viewModel()
            val ui by vm.ui.collectAsStateWithLifecycle()
            SettingsScreen(ui, theme, object : SettingsActions {
                override fun back() = back()
                override fun theme(mode: ThemeMode) = actions.setTheme(mode)
                override fun language(tag: String) = vm.language(tag)
                override fun notify(done: Boolean, on: Boolean) = vm.notify(done, on)
                override fun open(url: String) = actions.openUrl(url)
                override fun dismissSnack(seq: Long) = vm.dismiss(seq)
            })
        }
    }
    ToastHost(toast.notice?.let { noticeText(it) }, toast.seq, toast::dismiss, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.logout_title)) },
            text = { Text(pluralStringResource(R.plurals.logout_unsynced, pending, pending)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    actions.signOut()
                }, modifier = Modifier.testTag("sign-out-anyway")) { Text(stringResource(R.string.logout_anyway), color = Wgc.colors.dangerText) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.logout_cancel)) } },
            containerColor = Wgc.colors.surface,
        )
    }
}

@Composable
private fun ChoreRouteContent(id: Long, app: Application, nav: NavController, back: () -> Unit) {
    val vm: ChoreViewModel = viewModel(factory = ChoreViewModel.factory(app, id))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctl by vm.controller.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.load() } }
    ChoreScreen(ui, object : ChoreActions {
        override fun back() = back()
        override fun edit() = nav.navigate(EditRoute(choreId = id))
        override fun home() = nav.navigate(HouseholdsRoute()) { popUpTo(nav.graph.id) { inclusive = true } }
        override fun retry() = vm.retry()
        override fun more() { ctl?.more() }
        override fun tap() { ctl?.tap() }
        override fun undo() { ctl?.undo() }
        override fun otherDate() { ctl?.otherDate() }
        override fun openNew() { ctl?.openNew() }
        override fun openEntry(e: Entry) { ctl?.openEntry(e) }
        override fun closeSheet() { ctl?.closeSheet() }
        override fun saveSheet(doneOn: String, note: String?) { ctl?.saveSheet(doneOn, note) }
        override fun askDelete() { ctl?.askDelete() }
        override fun delete() { ctl?.delete() }
        override fun cancelDelete() { ctl?.cancelDelete() }
        override fun restore() { ctl?.restore() }
        override fun dismissSnack(seq: Long) { ctl?.dismissSnack(seq) }
    })
}

private fun NavController.openHousehold(id: Long) = navigate(HouseholdRoute(id)) {
    popUpTo(graph.id) { inclusive = true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HouseholdRouteContent(
    id: Long,
    userName: String,
    theme: ThemeMode,
    home: HomeViewModel,
    nav: NavController,
    bar: ((Boolean) -> Unit, () -> Unit, Long?) -> BarActions,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: HouseholdViewModel = viewModel(factory = HouseholdViewModel.factory(app, id))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctl by vm.controller.collectAsStateWithLifecycle()
    var switcher by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // on every resume and every 60 s while shown
    LaunchedEffect(vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.active = true
            try {
                while (true) {
                    vm.load()
                    delay(60_000)
                }
            } finally {
                vm.active = false
            }
        }
    }
    LaunchedEffect(ui.gone) {
        if (ui.gone) nav.navigate(HouseholdsRoute()) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    val act = object : ListActions {
        override fun open(choreId: Long) = nav.navigate(ChoreRoute(choreId))
        override fun long(choreId: Long) { ctl?.openSheet(choreId) }
        override fun done(choreId: Long) { ctl?.tap(choreId) }
        override fun undo() { ctl?.undo() }
        override fun otherDate() { ctl?.openSheetFromSnack() }
        override fun dismissSnack(seq: Long) { ctl?.dismissSnack(seq) }
        override fun refresh() { ctl?.refresh() ?: vm.retry() }
        override fun retry() = vm.retry()
        override fun addChore() = nav.navigate(EditRoute(householdId = id))
        override fun closeSheet() { ctl?.closeSheet() }
        override fun saveSheet(doneOn: String, note: String?) { ctl?.saveSheet(doneOn, note) }
    }
    val pending = ui.pending
    HouseholdScreen(
        ui, userName, theme,
        bar(
            { offline -> ctl?.showNotice(if (offline) Notice.Offline else Notice.Waiting(pending)) },
            {
                switcher = true
                home.loadHouseholds()
            },
            id,
        ),
        act,
    )

    if (switcher) {
        val hh by home.households.collectAsStateWithLifecycle()
        val list = hh.households.ifEmpty { listOfNotNull(ui.household) }
        ModalBottomSheet(
            onDismissRequest = { switcher = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Wgc.colors.surface,
            dragHandle = { SheetHandle() },
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 16.dp).navigationBarsPadding()) {
                Text(
                    stringResource(R.string.menu_your_households),
                    style = MaterialTheme.typography.titleMedium,
                    color = Wgc.colors.text,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
                for (h: Household in list) {
                    HouseholdItem(h, current = h.id == id, members = false) {
                        switcher = false
                        if (h.id != id) nav.openHousehold(h.id)
                    }
                }
                Box(Modifier.padding(8.dp)) {
                    SecondaryButton(stringResource(R.string.menu_new_household), {
                        switcher = false
                        nav.navigate(HouseholdsRoute(create = true))
                    }, Modifier.testTag("switcher-new"))
                }
            }
        }
    }
}
