package ch.tomigorn.wgchores.ui.screens

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
)

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
                    ui.households.isNotEmpty() -> HouseholdList(ui.households, onOpen)
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
                            { name = it.take(60) },
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
                    SecondaryButton(stringResource(R.string.households_new), { creating = true }, Modifier.testTag("household-new"))
                }
            }
        }
    }
}

@Composable
private fun HouseholdList(list: List<Household>, onOpen: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Wgc.colors.surface).border(1.dp, Wgc.colors.border, RoundedCornerShape(12.dp))) {
        list.forEachIndexed { i, h ->
            if (i > 0) HorizontalDivider(color = Wgc.colors.border)
            HouseholdItem(h, current = false) { onOpen(h.id) }
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

/** Screens of a later step: a back bar and a calm note. */
@Composable
fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Wgc.colors.bg)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.common_back), tint = Wgc.colors.text)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Wgc.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = Wgc.colors.border)
        Box(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(stringResource(R.string.common_coming_soon), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.muted)
        }
    }
}
