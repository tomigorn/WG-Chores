package ch.tomigorn.wgchores.ui.chore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.domain.By
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.ui.components.BarButton
import ch.tomigorn.wgchores.ui.components.ConfirmDialog
import ch.tomigorn.wgchores.ui.components.DoneSheetContent
import ch.tomigorn.wgchores.ui.components.DoneSnackbar
import ch.tomigorn.wgchores.ui.components.PageBar
import ch.tomigorn.wgchores.ui.components.PrimaryButton
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.components.SectionTitle
import ch.tomigorn.wgchores.ui.components.SnackAction
import ch.tomigorn.wgchores.ui.list.Snack
import ch.tomigorn.wgchores.ui.list.dayText
import ch.tomigorn.wgchores.ui.list.doneWhen
import ch.tomigorn.wgchores.ui.list.noticeText
import ch.tomigorn.wgchores.ui.screens.SheetHandle
import ch.tomigorn.wgchores.ui.screens.StateMessage
import ch.tomigorn.wgchores.ui.theme.Wgc
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

interface ChoreActions {
    fun back()
    fun edit()
    fun home()
    fun retry()
    fun more()
    fun tap()
    fun undo()
    fun otherDate()
    fun openNew()
    fun openEntry(e: Entry)
    fun closeSheet()
    fun saveSheet(doneOn: String, note: String?)
    fun askDelete()
    fun delete()
    fun cancelDelete()
    fun restore()
    fun dismissSnack(seq: Long)
}

@Composable
fun intervalWord(code: String?): String = stringResource(
    when (code) {
        "3d" -> R.string.interval_3d
        "1w" -> R.string.interval_1w
        "2w" -> R.string.interval_2w
        "3m" -> R.string.interval_3m
        "6m" -> R.string.interval_6m
        "1y" -> R.string.interval_1y
        else -> R.string.interval_1m
    },
)

@Composable
fun whoText(by: By): String = if (by.former) stringResource(R.string.chore_former, by.displayName) else by.displayName

@Composable
private fun dayCap(d: String, today: LocalDate): String = dayText(d, today).replaceFirstChar { it.uppercase() }

/** Name and Edit; category · interval; last done; usual gap; history; Done today / Other date… [inlineSheet] draws the sheet in place (screenshots). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoreScreen(ui: ChoreUi, act: ChoreActions, inlineSheet: Boolean = false) {
    val c = ui.chore
    Column(Modifier.fillMaxSize().background(Wgc.colors.bg)) {
        PageBar(c?.name ?: "", act::back) {
            if (c != null && !c.archived && !ui.offline && ui.phase == Phase.Ready) {
                BarButton(stringResource(R.string.chore_edit), act::edit, "chore-edit", icon = R.drawable.ic_pencil, label = stringResource(R.string.chore_edit_label, c.name))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.phase == Phase.Gone -> StateMessage(R.drawable.ic_circle_alert, null, stringResource(R.string.chore_not_found), stringResource(R.string.invite_go_home), act::home)
                ui.phase == Phase.Failed -> StateMessage(
                    if (ui.offline) R.drawable.ic_wifi_off else R.drawable.ic_circle_alert,
                    null,
                    stringResource(if (ui.offline) R.string.error_offline else R.string.error_generic),
                    stringResource(R.string.common_retry),
                    act::retry,
                )
                ui.phase == Phase.Loading || c == null -> Skeleton()
                else -> Body(ui, c, act)
            }
            SnackArea(ui, act, Modifier.align(Alignment.BottomCenter))
            if (inlineSheet && ui.sheet != null && c != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Wgc.colors.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))) {
                    SheetHandle()
                    SheetBody(ui, c, act)
                }
            }
        }
        if (c != null && ui.phase == Phase.Ready && !c.archived && !(inlineSheet && ui.sheet != null)) Footer(act)
    }
    val sheet = ui.sheet
    if (!inlineSheet && sheet != null && c != null) {
        ModalBottomSheet(
            onDismissRequest = act::closeSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Wgc.colors.surface,
            dragHandle = { SheetHandle() },
        ) { SheetBody(ui, c, act) }
    }
    ui.confirmDelete?.let { e ->
        ConfirmDialog(
            title = stringResource(R.string.entry_delete_title),
            text = stringResource(R.string.entry_delete_text, whoText(e.by), dayCap(e.doneOn, ui.today)),
            confirm = stringResource(R.string.entry_delete_confirm),
            cancel = stringResource(R.string.entry_keep),
            onConfirm = act::delete,
            onDismiss = act::cancelDelete,
        )
    }
}

@Composable
private fun SheetBody(ui: ChoreUi, c: Chore, act: ChoreActions) {
    when (val s = ui.sheet) {
        is EntrySheet.Edit -> DoneSheetContent(c.name, ui.today, s.entry.doneOn, s.entry.note, act::saveSheet, onDelete = act::askDelete)
        EntrySheet.New -> DoneSheetContent(c.name, ui.today, ui.today.toString(), null, act::saveSheet)
        null -> {}
    }
}

@Composable
private fun SnackArea(ui: ChoreUi, act: ChoreActions, modifier: Modifier) {
    val s = ui.snack ?: return
    val seq = ui.snackSeq
    LaunchedEffect(seq) {
        delay(6000)
        act.dismissSnack(seq)
    }
    when (s) {
        is Snack.Done -> {
            val actions = buildList {
                add(SnackAction(stringResource(R.string.done_undo), "snack-undo", act::undo))
                if (s.otherDate) add(SnackAction(stringResource(R.string.done_other_date), "snack-other-date", act::otherDate))
            }
            DoneSnackbar(s.prev.name, stringResource(R.string.done_snack, doneWhen(s.doneOn, ui.today)), actions, modifier)
        }
        is Snack.Text -> DoneSnackbar(null, noticeText(s.text), emptyList(), modifier)
    }
}

@Composable
private fun Body(ui: ChoreUi, c: Chore, act: ChoreActions) {
    val state = rememberLazyListState()
    val more by rememberUpdatedState(ui.next != null && !ui.loadingMore)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
            .distinctUntilChanged()
            .collect { near -> if (near && more) act.more() }
    }
    LaunchedEffect(ui.entries.size, ui.next) {
        val info = state.layoutInfo
        if (ui.next != null && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3) act.more()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // like the web app's container query: below 21em the day box and the history dates stack
        val em = with(LocalDensity.current) { 16.sp.toDp() }
        // the owner-approved mockup keeps the day box beside the text on a phone; it stacks only with large fonts
        val narrow = maxWidth / em < 16f
        LazyColumn(Modifier.fillMaxSize().testTag("chore-page"), state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)) {
            item(key = "meta") {
                val meta = c.category?.let { "$it · ${intervalWord(c.interval)}" } ?: intervalWord(c.interval)
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 16.dp))
            }
            if (ui.offline) {
                item(key = "offline") {
                    Text(stringResource(R.string.list_offline_note), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, modifier = Modifier.padding(bottom = 16.dp))
                }
            }
            if (c.archived) item(key = "archived") { Archived(act) }
            item(key = "last") { LastCard(c, ui.today, narrow) }
            c.usualInterval?.let { n ->
                item(key = "usual") {
                    Text(pluralStringResource(R.plurals.chore_usual, n, n), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
            }
            item(key = "history") { SectionTitle(stringResource(R.string.chore_history), Modifier.padding(top = 16.dp, bottom = 8.dp)) }
            if (ui.entries.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.chore_no_history), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, modifier = Modifier.padding(start = 4.dp)) }
            }
            if (ui.entries.isNotEmpty()) {
                item(key = "entries") {
                    ch.tomigorn.wgchores.ui.components.RowsCard {
                        ui.entries.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider(color = Wgc.colors.border)
                            EntryRow(e, ui.today, narrow) { act.openEntry(e) }
                        }
                    }
                }
            }
            if (ui.next != null) {
                item(key = "more") {
                    TextButton(onClick = act::more, enabled = !ui.loadingMore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("load-more")) {
                        Text(stringResource(R.string.chore_load_more), style = MaterialTheme.typography.labelMedium, color = Wgc.colors.accent)
                    }
                }
            }
            if (ui.entries.isNotEmpty()) {
                item(key = "hint") {
                    Text(
                        stringResource(R.string.chore_tap_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Wgc.colors.muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Archived(act: ChoreActions) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).border(1.dp, Wgc.colors.borderStrong, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.chore_archived_note), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.chore_restore), act::restore, Modifier.widthIn(max = 200.dp).testTag("restore"), icon = R.drawable.ic_rotate_ccw)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LastCard(c: Chore, today: LocalDate, narrow: Boolean) {
    val overdue = if (c.daysOverdue > 0) pluralStringResource(R.plurals.badge_overdue, c.daysOverdue, c.daysOverdue) else null
    val (bg, fg) = Wgc.colors.due[c.dueLevel] ?: Wgc.colors.due.getValue("never")
    val box = @Composable {
        Row(
            Modifier.heightIn(min = 56.dp).widthIn(min = 72.dp).background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (c.daysSince == null) {
                Text(stringResource(R.string.badge_never), color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, softWrap = false)
            } else {
                val unit = stringResource(R.string.badge_days, 0).replace("0", "").trim()
                Text("${c.daysSince}", color = fg, fontWeight = FontWeight.Bold, fontSize = 28.sp, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                Text(" $unit", color = fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
            }
        }
    }
    val lines = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val last = c.last
            Text(
                if (last == null) stringResource(R.string.chore_never) else stringResource(R.string.chore_last_done, dayText(last.doneOn, today).replace(' ', '\u00a0')),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Wgc.colors.text,
            )
            if (last != null) {
                val by = @Composable { Text(stringResource(R.string.chore_by, whoText(last.by)), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (narrow) {
                    by()
                    overdue?.let { OverdueText(it) }
                } else FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    by()
                    if (overdue != null) {
                        Text(
                            "· $overdue",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Wgc.colors.due.getValue("due").second,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("overdue"),
                        )
                    }
                }
            }
        }
    }
    val mod = Modifier.fillMaxWidth().background(Wgc.colors.surface, RoundedCornerShape(12.dp)).border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp)).padding(12.dp).testTag("last-done")
    if (narrow) {
        Column(mod, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            box()
            lines()
        }
    } else {
        Row(mod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            box()
            lines()
        }
    }
}

@Composable
private fun OverdueText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Wgc.colors.due.getValue("due").second, maxLines = 1, softWrap = false, modifier = Modifier.testTag("overdue"))
}

@Composable
private fun EntryRow(e: Entry, today: LocalDate, narrow: Boolean, onClick: () -> Unit) {
    val day = dayCap(e.doneOn, today)
    val who = whoText(e.by)
    val label = stringResource(R.string.chore_entry_label, day, who) + (e.note?.let { ", “$it”" } ?: "") + if (e.waiting) ", " + stringResource(R.string.chore_waiting) else ""
    run {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).testTag("entry-${e.id}").clearAndSetSemantics { contentDescription = label }.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val dateText = @Composable { m: Modifier -> Text(day, style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted, maxLines = 1, softWrap = false, modifier = m) }
            val whoCol = @Composable { m: Modifier ->
                Column(m) {
                    Text(who, style = MaterialTheme.typography.bodyLarge, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    e.note?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = Wgc.colors.muted) }
                }
            }
            if (narrow) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dateText(Modifier)
                    whoCol(Modifier)
                }
            } else {
                val em = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.fontSize.toDp() }
                dateText(Modifier.widthIn(min = em * 5.5f).padding(top = 2.dp))
                whoCol(Modifier.weight(1f))
            }
            if (e.waiting) {
                val glass = with(LocalDensity.current) { 14.sp.toDp() }
                Icon(painterResource(R.drawable.ic_hourglass), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.size(glass).align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun Footer(act: ChoreActions) {
    Column(Modifier.fillMaxWidth().background(Wgc.colors.surface)) {
        HorizontalDivider(color = Wgc.colors.border)
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 12.dp)) {
            val em = with(LocalDensity.current) { 16.sp.toDp() }
            val done = @Composable { m: Modifier -> PrimaryButton(stringResource(R.string.chore_done_today), act::tap, m.testTag("chore-done"), icon = R.drawable.ic_check) }
            val other = @Composable { m: Modifier -> SecondaryButton(stringResource(R.string.chore_other_date), act::openNew, m.testTag("chore-other-date")) }
            if (maxWidth / em < 20f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    done(Modifier)
                    other(Modifier)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    done(Modifier.weight(1f))
                    other(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Skeleton() {
    Column(Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth(0.4f).height(12.dp).background(Wgc.colors.border, RoundedCornerShape(6.dp)))
        Box(Modifier.fillMaxWidth().height(80.dp).background(Wgc.colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp)))
    }
}
