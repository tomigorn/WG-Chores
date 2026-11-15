package ch.tomigorn.wgchores.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.ui.theme.Wgc
import kotlinx.coroutines.delay

/** Back arrow, title (2 lines at most), optional action on the right. */
@Composable
fun PageBar(title: String, onBack: () -> Unit, right: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(Wgc.colors.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.common_back), tint = Wgc.colors.text)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.125f),
                color = Wgc.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
            )
            right?.invoke()
        }
        HorizontalDivider(color = Wgc.colors.border)
    }
}

/** Text action in a page bar ("Edit", "Save"). */
@Composable
fun BarButton(text: String, onClick: () -> Unit, tag: String, enabled: Boolean = true, icon: Int? = null, label: String? = null) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag), shape = RoundedCornerShape(8.dp)) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = if (enabled) Wgc.colors.accent else Wgc.colors.muted, modifier = Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Wgc.colors.accent else Wgc.colors.muted,
            maxLines = 1,
            softWrap = false,
            modifier = if (label != null) Modifier.semantics { contentDescription = label } else Modifier,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = Wgc.colors.muted,
        modifier = modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
    )
}

/** Surface card with rows separated by lines. */
@Composable
fun RowsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Wgc.colors.surface).border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp)),
        content = content,
    )
}

@Composable
fun PadCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Wgc.colors.surface, RoundedCornerShape(12.dp)).border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Confirm before something that cannot be undone; [danger] colours the confirm action. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    cancel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
    enabled: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Wgc.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted)
                extra?.invoke(this)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.testTag("confirm")) {
                Text(confirm, color = if (!enabled) Wgc.colors.muted else if (danger) Wgc.colors.dangerText else Wgc.colors.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("confirm-cancel")) { Text(cancel, color = Wgc.colors.text) } },
        containerColor = Wgc.colors.surface,
    )
}

/** A short text at the bottom for a few seconds (same bar as the list's). */
@Composable
fun ToastHost(text: String?, seq: Long, onDone: (Long) -> Unit, modifier: Modifier = Modifier, actions: List<SnackAction> = emptyList(), strong: String? = null) {
    if (text == null) return
    LaunchedEffect(seq) {
        delay(6000)
        onDone(seq)
    }
    Box(modifier.windowInsetsPadding(WindowInsets.navigationBars)) { DoneSnackbar(strong, text, actions) }
}

/** True when [width] holds fewer than [ems] of 16 sp text: small phone or large font. */
@Composable
fun narrow(width: androidx.compose.ui.unit.Dp, ems: Float = 14f): Boolean {
    val em = with(androidx.compose.ui.platform.LocalDensity.current) { 16.sp.toDp() }
    return width / em < ems
}

/** List line: icon or avatar, text, an action on the right; when narrow the action moves below the text, so names stay readable. */
@Composable
fun ItemRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = narrow(maxWidth)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (stacked) 8.dp else 0.dp)) {
                Column(content = body)
                if (stacked) trailing()
            }
            if (!stacked) trailing()
        }
    }
}

/** Two buttons side by side, one above the other when narrow. */
@Composable
fun ButtonPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (narrow(maxWidth, 16f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier)
                second(Modifier)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}
