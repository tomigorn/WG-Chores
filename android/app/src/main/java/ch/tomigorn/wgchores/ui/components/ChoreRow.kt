package ch.tomigorn.wgchores.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.ui.list.badgeLabel
import ch.tomigorn.wgchores.ui.list.badgeText
import ch.tomigorn.wgchores.ui.list.dayText
import ch.tomigorn.wgchores.ui.theme.Wgc
import java.time.LocalDate

/** Days-since pill in the due colour; read out as "26 days since, 19 days overdue". */
@Composable
fun DueBadge(c: Chore, modifier: Modifier = Modifier) {
    val (bg, fg) = Wgc.colors.due[c.dueLevel] ?: Wgc.colors.due.getValue("never")
    val label = badgeLabel(c)
    Box(
        modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 24.dp)
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            badgeText(c),
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", lineHeight = 16.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The ✓ button: outlined, filled with the accent when the chore was done today. */
@Composable
fun DoneButton(c: Chore, onClick: () -> Unit) {
    val today = c.daysSince == 0
    val desc = stringResource(R.string.list_mark_done, c.name)
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag("done-${c.id}").semantics { contentDescription = desc }) {
        Box(
            Modifier
                .size(32.dp)
                .background(if (today) Wgc.colors.accent else Color.Transparent, CircleShape)
                .border(1.5.dp, if (today) Wgc.colors.accent else Wgc.colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = if (today) Wgc.colors.accentText else Wgc.colors.muted, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhoWhen(c: Chore, today: LocalDate, wrap: Boolean) {
    val style = MaterialTheme.typography.bodyMedium
    val last = c.last
    if (last == null) {
        Text(stringResource(R.string.list_never_done), style = style, color = Wgc.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val who = last.by.displayName
    val wh = dayText(last.doneOn, today)
    if (!wrap) {
        Text("$who · $wh", style = style, color = Wgc.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    // who and when wrap as two units on narrow rows, never inside the date
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$who ·", style = style, color = Wgc.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(wh, style = style, color = Wgc.colors.muted, maxLines = 1, softWrap = false)
    }
}

/** One chore; [stacked] (narrow list or large text): name up to two lines, then who · when, then badge and ✓. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChoreRow(c: Chore, today: LocalDate, stacked: Boolean, onOpen: () -> Unit, onLong: () -> Unit, onDone: () -> Unit) {
    val nameStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight(560))
    val longLabel = stringResource(R.string.done_other_date)
    val base = Modifier
        .fillMaxWidth()
        .background(Wgc.colors.surface)
        .combinedClickable(onClick = onOpen, onLongClick = onLong, onLongClickLabel = longLabel)
        .testTag("row-${c.id}")
    if (!stacked) {
        Row(
            base.heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.name, style = nameStyle, color = Wgc.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                WhoWhen(c, today, wrap = false)
            }
            DueBadge(c)
            DoneButton(c, onDone)
        }
    } else {
        Column(base.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Text(c.name, style = nameStyle, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.size(2.dp))
            Box(Modifier.padding(end = 8.dp)) { WhoWhen(c, today, wrap = true) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DueBadge(c)
                DoneButton(c, onDone)
            }
        }
    }
}
