package ch.tomigorn.wgchores.ui.screens

import ch.tomigorn.wgchores.domain.Rules
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.ui.components.Alert
import ch.tomigorn.wgchores.ui.components.BarActions
import ch.tomigorn.wgchores.ui.components.Field
import ch.tomigorn.wgchores.ui.components.PrimaryButton
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.components.TopBar
import ch.tomigorn.wgchores.ui.theme.Wgc

data class HouseholdsUi(
    val households: List<Household> = emptyList(),
    val loaded: Boolean = false,
    val offline: Boolean = false,
    val failed: Boolean = false,
    val busy: Boolean = false,
    /** Text resource of the last create error. */
    val error: Int? = null,
    val join: JoinUi = JoinUi(),
)

/** "Join with an invite link": paste, preview, join. */
data class JoinUi(
    val link: String = "",
    val token: String? = null, // leak-check: ok
    val preview: ch.tomigorn.wgchores.domain.InvitePreview? = null,
    val error: Int? = null,
    val errorArg: String? = null,
    val busy: Boolean = false,
)

interface JoinActions {
    fun link(v: String)
    fun show()
    fun join()
}

/** "My households": open one, or create a new one (name + Create). */
@Composable
fun HouseholdsScreen(
    ui: HouseholdsUi,
    userName: String,
    pending: Int,
    theme: ThemeMode,
    startCreating: Boolean,
    bar: BarActions,
    onOpen: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRetry: () -> Unit,
    onSettings: (Long) -> Unit = {},
    join: JoinActions? = null,
) {
    var creating by rememberSaveable { mutableStateOf(startCreating) }
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Wgc.colors.bg)) {
        TopBar(null, userName, pending, ui.offline, theme, bar)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.households_title), style = MaterialTheme.typography.headlineSmall, color = Wgc.colors.text, modifier = Modifier.semantics { heading() })
                when {
                    !ui.loaded && ui.failed -> {
                        Alert(stringResource(if (ui.offline) R.string.error_offline else R.string.error_generic))
                        SecondaryButton(stringResource(R.string.common_retry), onRetry)
                    }
                    ui.loaded && ui.households.isEmpty() && !creating -> {
                        Text(stringResource(R.string.households_empty_title), style = MaterialTheme.typography.titleLarge, color = Wgc.colors.text)
                        Text(stringResource(R.string.households_empty_text), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted)
                    }
                    ui.households.isNotEmpty() -> HouseholdList(ui.households, onOpen, onSettings)
                }
                if (creating) {
                    Column(
                        Modifier.fillMaxWidth().background(Wgc.colors.surface, RoundedCornerShape(12.dp)).border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.households_new), style = MaterialTheme.typography.titleMedium, color = Wgc.colors.text)
                        Field(
                            stringResource(R.string.households_name),
                            name,
                            { name = Rules.cap(it, 60) },
                            modifier = Modifier.testTag("household-name"),
                            hint = stringResource(R.string.households_name_placeholder),
                            error = ui.error?.let { stringResource(it) },
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            onIme = { if (name.isNotBlank()) onCreate(name.trim()) },
                        )
                        PrimaryButton(stringResource(R.string.households_create), { onCreate(name.trim()) }, Modifier.testTag("household-create"), enabled = name.isNotBlank(), busy = ui.busy)
                        SecondaryButton(stringResource(R.string.households_cancel), { creating = false })
                    }
                } else if (ui.loaded || ui.households.isNotEmpty()) {
                    SecondaryButton(stringResource(R.string.households_new), { creating = true }, Modifier.testTag("household-new"), icon = R.drawable.ic_plus)
                }
                if (join != null && (ui.loaded || ui.households.isNotEmpty())) JoinCard(ui.join, join)
            }
        }
    }
}

@Composable
private fun HouseholdList(list: List<Household>, onOpen: (Long) -> Unit, onSettings: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Wgc.colors.surface).border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp))) {
        list.forEachIndexed { i, h ->
            if (i > 0) HorizontalDivider(color = Wgc.colors.border)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { HouseholdItem(h, current = false) { onOpen(h.id) } }
                androidx.compose.material3.IconButton(onClick = { onSettings(h.id) }, modifier = Modifier.testTag("household-settings-${h.id}")) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.households_settings_for, h.name), tint = Wgc.colors.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun JoinCard(j: JoinUi, act: JoinActions) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(Wgc.colors.surface, RoundedCornerShape(12.dp)).border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.join_title), style = MaterialTheme.typography.titleMedium, color = Wgc.colors.text, modifier = Modifier.semantics { heading() })
        val p = j.preview
        if (p == null) {
            Field(
                stringResource(R.string.join_link), j.link, act::link, Modifier.testTag("join-link"),
                hint = stringResource(R.string.join_hint),
                error = j.error?.let { if (j.errorArg != null) stringResource(it, j.errorArg) else stringResource(it) },
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                onIme = act::show,
            )
            SecondaryButton(stringResource(R.string.join_show), act::show, Modifier.testTag("join-show"), enabled = j.link.isNotBlank(), busy = j.busy, icon = R.drawable.ic_link)
        } else {
            Text(stringResource(R.string.invite_preview, p.invitedBy), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted)
            Text(p.householdName, style = MaterialTheme.typography.titleLarge, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.valid) {
                Text(ch.tomigorn.wgchores.ui.household.expiryText(ch.tomigorn.wgchores.domain.Rules.expiresIn(p.expiresAt, java.time.Instant.now())), style = MaterialTheme.typography.bodySmall, color = Wgc.colors.muted)
                j.error?.let { Alert(stringResource(it)) }
                PrimaryButton(stringResource(R.string.invite_join, p.householdName), act::join, Modifier.testTag("join-submit"), busy = j.busy)
            } else {
                Alert(stringResource(j.error ?: R.string.error_invite_not_found) + " " + stringResource(R.string.invite_invalid_hint))
            }
            SecondaryButton(stringResource(R.string.households_cancel), { act.link("") }, Modifier.testTag("join-cancel"))
        }
    }
}

@Composable
fun HouseholdItem(h: Household, current: Boolean, members: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag("household-${h.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_house), contentDescription = null, tint = Wgc.colors.muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (members) Text(pluralStringResource(R.plurals.households_members, h.memberCount, h.memberCount), style = MaterialTheme.typography.bodySmall, color = Wgc.colors.muted, maxLines = 1)
        }
        if (current) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Wgc.colors.accent, modifier = Modifier.size(20.dp))
    }
}
