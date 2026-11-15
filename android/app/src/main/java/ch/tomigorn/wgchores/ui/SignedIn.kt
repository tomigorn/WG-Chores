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
import ch.tomigorn.wgchores.ui.screens.PlaceholderScreen
import ch.tomigorn.wgchores.ui.screens.SheetHandle
import ch.tomigorn.wgchores.ui.theme.Wgc
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable data class HouseholdRoute(val id: Long)
@Serializable data class HouseholdsRoute(val create: Boolean = false)
@Serializable data class PlaceholderRoute(val title: String)

/** Signed in: the last household (else the household list), with placeholders for the screens of later steps. */
@Composable
fun SignedInArea(state: AuthState.SignedIn, theme: ThemeMode, actions: AppActions) {
    val home: HomeViewModel = viewModel(key = "home|${state.server}|${state.userId}")
    val start by home.start.collectAsStateWithLifecycle()
    val pending by home.pending.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    val s = start ?: return Box(Modifier.fillMaxSize().background(Wgc.colors.bg))
    val nav = rememberNavController()
    val placeholders = mapOf(
        Dest.Settings to stringResource(R.string.nav_settings),
        Dest.Account to stringResource(R.string.nav_account),
    )

    fun bar(onStatus: (Boolean) -> Unit = {}, onSwitch: () -> Unit = {}) = object : BarActions {
        override fun switchHousehold() = onSwitch()
        override fun status(offline: Boolean) = onStatus(offline)
        override fun theme(mode: ThemeMode) = actions.setTheme(mode)
        override fun signOut() {
            if (pending > 0) confirmSignOut = true else actions.signOut()
        }
        override fun navigate(d: Dest) = when (d) {
            Dest.Households -> nav.navigate(HouseholdsRoute())
            Dest.NewHousehold -> nav.navigate(HouseholdsRoute(create = true))
            else -> nav.navigate(PlaceholderRoute(placeholders.getValue(d)))
        }
    }

    val startRoute: Any = when (s) {
        is Start.Household -> HouseholdRoute(s.id)
        Start.Households -> HouseholdsRoute()
    }
    NavHost(nav, startDestination = startRoute) {
        composable<HouseholdRoute> { entry ->
            HouseholdRouteContent(entry.toRoute<HouseholdRoute>().id, state.userName, theme, home, nav, ::bar)
        }
        composable<HouseholdsRoute> { entry ->
            val ui by home.households.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { home.loadHouseholds() }
            HouseholdsScreen(
                ui, state.userName, pending, theme, entry.toRoute<HouseholdsRoute>().create, bar(),
                onOpen = { id -> nav.openHousehold(id) },
                onCreate = { name -> home.create(name) { id -> nav.openHousehold(id) } },
                onRetry = home::loadHouseholds,
            )
        }
        composable<PlaceholderRoute> { entry -> PlaceholderScreen(entry.toRoute<PlaceholderRoute>().title) { nav.popBackStack() } }
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
    bar: ((Boolean) -> Unit, () -> Unit) -> BarActions,
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
        override fun open(choreId: Long) {
            ui.chores.find { it.id == choreId }?.let { nav.navigate(PlaceholderRoute(it.name)) }
        }
        override fun long(choreId: Long) { ctl?.openSheet(choreId) }
        override fun done(choreId: Long) { ctl?.tap(choreId) }
        override fun undo() { ctl?.undo() }
        override fun otherDate() { ctl?.openSheetFromSnack() }
        override fun dismissSnack(seq: Long) { ctl?.dismissSnack(seq) }
        override fun refresh() { ctl?.refresh() ?: vm.retry() }
        override fun retry() = vm.retry()
        override fun addChore() = nav.navigate(PlaceholderRoute(app.getString(R.string.list_add_chore)))
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
