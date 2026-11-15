package ch.tomigorn.wgchores.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.domain.Words
import ch.tomigorn.wgchores.ui.theme.Wgc

enum class Dest { Households, NewHousehold, Settings, Account }

interface BarActions {
    fun switchHousehold()
    fun status(offline: Boolean)
    fun navigate(d: Dest)
    fun theme(mode: ThemeMode)
    fun signOut()
}

@Composable
fun Initials(name: String, size: Int = 32) {
    Box(
        Modifier.size(size.dp).background(Wgc.colors.surface, CircleShape).border(1.dp, Wgc.colors.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // sized to the circle, not the font scale, so the initials never spill out
        val fs = with(LocalDensity.current) { (size * 0.4).dp.toSp() }
        Text(Words.initials(name), color = Wgc.colors.muted, fontWeight = FontWeight.SemiBold, fontSize = fs, lineHeight = fs, maxLines = 1, softWrap = false)
    }
}

/** Name (tap = switch), sync chip, menu, avatar; if the name would show under 9 characters, chip, menu and avatar move to a second row. */
@Composable
fun TopBar(title: String?, userName: String, pending: Int, offline: Boolean, theme: ThemeMode, actions: BarActions) {
    val chip = pending > 0 || offline
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().background(Wgc.colors.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val chipText = if (pending > 0) "$pending" else stringResource(R.string.list_offline)
            val titleStyle = titleStyle()
            val chipStyle = chipStyle()
            fun width(text: String, style: TextStyle) = with(density) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp() }
            // paddings, spacing, chevron, menu and avatar of the one-row bar
            val fixed = 12.dp + 24.dp + 22.dp + 4.dp + 48.dp + 4.dp + 48.dp
            val chipWidth = if (chip) 4.dp + 22.dp + 18.dp + width(chipText, chipStyle) else 0.dp
            val compact = title != null && maxWidth - fixed - chipWidth < width(title.take(9) + if (title.length > 9) "…" else "", titleStyle)
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (title != null) {
                        TitleButton(title, maxLines = if (compact && density.fontScale >= 1.5f) 2 else 1, actions, Modifier.weight(1f))
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    if (!compact) {
                        if (chip) SyncChip(pending, actions)
                        MenuAndAvatar(userName, theme, actions)
                    }
                }
                if (compact) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (chip) SyncChip(pending, actions)
                        Box(Modifier.weight(1f))
                        MenuAndAvatar(userName, theme, actions)
                    }
                }
            }
        }
        HorizontalDivider(color = Wgc.colors.border)
    }
}

private val TITLE_SIZE = 18.sp

@Composable
private fun titleStyle() = MaterialTheme.typography.titleMedium.copy(fontSize = TITLE_SIZE, lineHeight = TITLE_SIZE * 1.25f)

@Composable
private fun chipStyle() = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)

@Composable
private fun MenuAndAvatar(userName: String, theme: ThemeMode, actions: BarActions) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            val menuLabel = stringResource(R.string.menu_open)
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu").semantics { contentDescription = menuLabel }) {
                Icon(painterResource(R.drawable.ic_menu), contentDescription = null, tint = Wgc.colors.text)
            }
            Menu(menu, onClose = { menu = false }, theme, actions)
        }
        val accountLabel = stringResource(R.string.nav_account)
        IconButton(onClick = { actions.navigate(Dest.Account) }, modifier = Modifier.semantics { contentDescription = accountLabel }) {
            Initials(userName)
        }
    }
}

@Composable
private fun TitleButton(title: String, maxLines: Int, actions: BarActions, modifier: Modifier) {
    val label = stringResource(R.string.menu_hh_switch_label, title)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .testTag("household-switch")
            .clickable(onClick = actions::switchHousehold)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // two lines only where they break between words; a word too long for a line gets one line with an ellipsis
        var lines by remember(title, maxLines) { mutableStateOf(maxLines) }
        Text(
            title,
            style = titleStyle(),
            color = Wgc.colors.text,
            maxLines = lines,
            onTextLayout = { r ->
                val end = if (r.lineCount > 1) r.getLineEnd(0) else 0
                if (lines > 1 && end in 1 until title.length && !title[end - 1].isWhitespace() && !title[end].isWhitespace()) lines = 1
            },
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("household-title").semantics { hideFromAccessibility() },
        )
        Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SyncChip(pending: Int, actions: BarActions) {
    val desc = if (pending > 0) pluralStringResource(R.plurals.sync_waiting_label, pending, pending) else stringResource(R.string.list_offline_note)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .testTag("sync-chip")
            .clip(RoundedCornerShape(8.dp))
            .clickable { actions.status(pending == 0) }
            .clearAndSetSemantics {
                contentDescription = desc
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.border(1.dp, Wgc.colors.border, RoundedCornerShape(50)).background(Wgc.colors.surface, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(if (pending > 0) R.drawable.ic_hourglass else R.drawable.ic_wifi_off), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.size(14.dp))
            Text(if (pending > 0) "$pending" else stringResource(R.string.list_offline), style = chipStyle(), color = Wgc.colors.text, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun MenuItem(icon: Int, label: Int, tag: String, onClose: () -> Unit, run: () -> Unit) = DropdownMenuItem(
    text = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = Wgc.colors.text) },
    leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.size(20.dp)) },
    onClick = {
        onClose()
        run()
    },
    modifier = Modifier.testTag(tag),
)

@Composable
private fun Menu(open: Boolean, onClose: () -> Unit, theme: ThemeMode, actions: BarActions) {
    DropdownMenu(expanded = open, onDismissRequest = onClose, shape = RoundedCornerShape(12.dp), containerColor = Wgc.colors.surface) {
        MenuItem(R.drawable.ic_house, R.string.nav_households, "menu-households", onClose) { actions.navigate(Dest.Households) }
        MenuItem(R.drawable.ic_plus, R.string.menu_new_household, "menu-new-household", onClose) { actions.navigate(Dest.NewHousehold) }
        MenuItem(R.drawable.ic_settings, R.string.nav_settings, "menu-settings", onClose) { actions.navigate(Dest.Settings) }
        MenuItem(R.drawable.ic_user_round, R.string.nav_account, "menu-account", onClose) { actions.navigate(Dest.Account) }
        HorizontalDivider(color = Wgc.colors.border)
        Text(
            stringResource(R.string.theme_label),
            style = MaterialTheme.typography.labelMedium,
            color = Wgc.colors.muted,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        val labels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
        ThemeMode.entries.forEachIndexed { i, mode ->
            DropdownMenuItem(
                text = { Text(stringResource(labels[i]), style = MaterialTheme.typography.bodyLarge, color = Wgc.colors.text) },
                leadingIcon = {
                    if (theme == mode) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Wgc.colors.accent, modifier = Modifier.size(20.dp))
                    else Box(Modifier.size(20.dp))
                },
                onClick = { actions.theme(mode) },
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    selected = theme == mode
                },
            )
        }
        HorizontalDivider(color = Wgc.colors.border)
        MenuItem(R.drawable.ic_log_out, R.string.nav_logout, "sign-out", onClose) { actions.signOut() }
    }
}
