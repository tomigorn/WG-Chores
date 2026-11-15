package ch.tomigorn.wgchores.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.domain.ChoreGroup
import ch.tomigorn.wgchores.domain.Chores
import ch.tomigorn.wgchores.ui.components.BarActions
import ch.tomigorn.wgchores.ui.components.ChoreRow
import ch.tomigorn.wgchores.ui.components.DoneSheetContent
import ch.tomigorn.wgchores.ui.components.DoneSnackbar
import ch.tomigorn.wgchores.ui.components.SnackAction
import ch.tomigorn.wgchores.ui.components.TopBar
import ch.tomigorn.wgchores.ui.list.ListController
import ch.tomigorn.wgchores.ui.list.ListUi
import ch.tomigorn.wgchores.ui.list.Snack
import ch.tomigorn.wgchores.ui.list.currentLocale
import ch.tomigorn.wgchores.ui.list.doneWhen
import ch.tomigorn.wgchores.ui.list.noticeText
import ch.tomigorn.wgchores.ui.theme.Wgc
import kotlinx.coroutines.delay

interface ListActions {
    fun open(choreId: Long)
    fun long(choreId: Long)
    fun done(choreId: Long)
    fun undo()
    fun otherDate()
    fun dismissSnack(seq: Long)
    fun refresh()
    fun retry()
    fun addChore()
    fun closeSheet()
    fun saveSheet(doneOn: String, note: String?)
}

/** The chore list of a household with its bar, snackbar and done sheet. [inlineSheet] draws the sheet in place (screenshots). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(ui: ListUi, userName: String, theme: ThemeMode, bar: BarActions, act: ListActions, inlineSheet: Boolean = false) {
    Column(Modifier.fillMaxSize().background(Wgc.colors.bg)) {
        TopBar(ui.household?.name ?: "", userName, ui.pending, ui.offline, theme, bar)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = act::refresh, modifier = Modifier.fillMaxSize()) {
                when {
                    !ui.loaded && ui.failed -> StateMessage(
                        icon = if (ui.offline) R.drawable.ic_wifi_off else R.drawable.ic_circle_alert,
                        title = null,
                        text = stringResource(if (ui.offline) R.string.error_offline else R.string.error_generic),
                        button = stringResource(R.string.common_retry),
                        onClick = act::retry,
                    )
                    !ui.loaded -> Skeleton()
                    ui.chores.isEmpty() -> StateMessage(
                        icon = R.drawable.ic_list_checks,
                        title = stringResource(R.string.list_empty_title),
                        text = stringResource(R.string.list_empty_text),
                        button = stringResource(R.string.list_add_chore),
                        onClick = act::addChore,
                        primary = true,
                    )
                    else -> ChoreList(ui, act)
                }
            }
            SnackHost(ui, act, Modifier.align(Alignment.BottomCenter))
            if (inlineSheet && ui.sheet != null) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)))
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Wgc.colors.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                ) {
                    SheetHandle()
                    DoneSheetContent(ui.sheet.chore.name, ui.today, ui.sheet.doneOn, ui.sheet.note, act::saveSheet)
                }
            }
        }
    }
    val sheet = ui.sheet
    if (!inlineSheet && sheet != null) {
        ModalBottomSheet(
            onDismissRequest = act::closeSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Wgc.colors.surface,
            dragHandle = { SheetHandle() },
        ) {
            DoneSheetContent(sheet.chore.name, ui.today, sheet.doneOn, sheet.note, act::saveSheet)
        }
    }
}

@Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).background(Wgc.colors.border, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun SnackHost(ui: ListUi, act: ListActions, modifier: Modifier) {
    val s = ui.snack ?: return
    val seq = ui.snackSeq
    LaunchedEffect(seq) {
        delay(ListController.SNACK_MS)
        act.dismissSnack(seq)
    }
    val mod = modifier.windowInsetsPadding(WindowInsets.navigationBars)
    when (s) {
        is Snack.Done -> {
            val actions = buildList {
                add(SnackAction(stringResource(R.string.done_undo), "snack-undo", act::undo))
                if (s.otherDate) add(SnackAction(stringResource(R.string.done_other_date), "snack-other-date", act::otherDate))
            }
            DoneSnackbar(s.prev.name, stringResource(R.string.done_snack, doneWhen(s.doneOn, ui.today)), actions, mod)
        }
        is Snack.Text -> DoneSnackbar(null, noticeText(s.text), emptyList(), mod)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChoreList(ui: ListUi, act: ListActions) {
    val locale = currentLocale()
    val groups = remember(ui.chores, locale) { Chores.group(ui.chores, locale) }
    val other = stringResource(R.string.list_other_category)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // like the web app's container query: below 16em the row stacks
        val em = with(LocalDensity.current) { 16.sp.toDp() }
        val stacked = maxWidth / em < 16f
        LazyColumn(
            Modifier.fillMaxSize().testTag("chore-list"),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (ui.offline) {
                item(key = "offline") {
                    Text(
                        stringResource(R.string.list_offline_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Wgc.colors.muted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                }
            }
            for (g in groups) group(g, other, ui, stacked, act)
            item(key = "end") {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(
                        onClick = act::addChore,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Wgc.colors.borderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Wgc.colors.surface, contentColor = Wgc.colors.text),
                        modifier = Modifier.testTag("add-chore"),
                    ) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.list_add_chore), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.group(g: ChoreGroup, other: String, ui: ListUi, stacked: Boolean, act: ListActions) {
    stickyHeader(key = "cat-${g.category}", contentType = "header") {
        Text(
            g.category ?: other,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Wgc.colors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(Wgc.colors.bg)
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                .semantics { heading() },
        )
    }
    item(key = "top-${g.category}", contentType = "line") { HorizontalDivider(color = Wgc.colors.border) }
    g.chores.forEachIndexed { i, c ->
        item(key = c.id, contentType = "row") {
            Column {
                if (i > 0) HorizontalDivider(color = Wgc.colors.border)
                ChoreRow(c, ui.today, stacked, onOpen = { act.open(c.id) }, onLong = { act.long(c.id) }, onDone = { act.done(c.id) })
            }
        }
    }
    item(key = "bottom-${g.category}", contentType = "line") { HorizontalDivider(color = Wgc.colors.border) }
}

@Composable
private fun Skeleton() {
    val label = stringResource(R.string.list_loading)
    Column(Modifier.fillMaxWidth().padding(top = 40.dp).background(Wgc.colors.surface).semantics { contentDescription = label }) {
        repeat(6) { i ->
            if (i > 0) HorizontalDivider(color = Wgc.colors.border)
            Column(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                Box(Modifier.fillMaxWidth(0.45f).height(10.dp).background(Wgc.colors.border, RoundedCornerShape(5.dp)))
                Box(Modifier.fillMaxWidth(0.28f).height(10.dp).background(Wgc.colors.border.copy(alpha = 0.6f), RoundedCornerShape(5.dp)))
            }
        }
    }
}

@Composable
fun StateMessage(icon: Int, title: String?, text: String, button: String?, onClick: () -> Unit, primary: Boolean = false) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.size(32.dp))
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Wgc.colors.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, textAlign = TextAlign.Center)
        if (button != null) {
            Spacer(Modifier.size(4.dp))
            Row(Modifier.widthIn(max = 320.dp)) {
                if (primary) {
                    ch.tomigorn.wgchores.ui.components.PrimaryButton(button, onClick, Modifier.testTag("state-action"))
                } else {
                    ch.tomigorn.wgchores.ui.components.SecondaryButton(button, onClick, Modifier.testTag("state-action"))
                }
            }
        }
    }
}
