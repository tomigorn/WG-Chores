package ch.tomigorn.wgchores.ui.chore

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.WgcApp
import ch.tomigorn.wgchores.domain.Category
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Intervals
import ch.tomigorn.wgchores.domain.Rules
import ch.tomigorn.wgchores.ui.ErrorText
import ch.tomigorn.wgchores.ui.components.Alert
import ch.tomigorn.wgchores.ui.components.BarButton
import ch.tomigorn.wgchores.ui.components.narrowScreen
import ch.tomigorn.wgchores.ui.components.ConfirmDialog
import ch.tomigorn.wgchores.ui.components.Field
import ch.tomigorn.wgchores.ui.components.PageBar
import ch.tomigorn.wgchores.ui.components.SecondaryButton
import ch.tomigorn.wgchores.ui.screens.StateMessage
import ch.tomigorn.wgchores.ui.theme.Wgc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EditDone {
    data class Created(val householdId: Long, val name: String) : EditDone
    data class Saved(val choreId: Long) : EditDone
    data class Archived(val householdId: Long, val name: String) : EditDone
}

data class EditUi(
    val editing: Boolean,
    val phase: Phase = Phase.Loading,
    val chore: Chore? = null,
    val householdId: Long? = null,
    val categories: List<Category> = emptyList(),
    val name: String = "",
    val category: String = "",
    val interval: String? = null,
    val renameAll: Boolean = false,
    val busy: Boolean = false,
    val error: Int? = null,
    /** Reason inside [error] ("…keep their category: <reason>"). */
    val errorDetail: Int? = null,
    val confirmArchive: Boolean = false,
    val done: EditDone? = null,
) {
    val renameCount: Int get() = Rules.renameAllCount(chore?.category, category, categories)
    val canSave: Boolean get() = phase == Phase.Ready && !busy && name.isNotBlank()
}

interface EditActions {
    fun cancel()
    fun save()
    fun retry()
    fun name(v: String)
    fun category(v: String)
    fun interval(code: String)
    fun renameAll(on: Boolean)
    fun askArchive()
    fun archive()
    fun closeArchive()
}

/** New chore (in [householdId]) or edit of [choreId]: name, category, "Roughly how often", archive. */
class ChoreEditViewModel(app: Application, private val choreId: Long?, private val householdId: Long?) : AndroidViewModel(app) {
    private val wgc = app as WgcApp
    val ui = MutableStateFlow(EditUi(editing = choreId != null))

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(phase = Phase.Loading, error = null) }
        viewModelScope.launch {
            try {
                val a = wgc.account() ?: throw ch.tomigorn.wgchores.api.OfflineException()
                val c = choreId?.let { a.api.chore(it) }
                val hid = c?.householdId ?: householdId
                val cats = hid?.let { a.api.categories(it) }.orEmpty()
                ui.update {
                    it.copy(
                        phase = Phase.Ready, chore = c, householdId = hid, categories = cats,
                        name = c?.name ?: it.name, category = c?.category ?: it.category, interval = c?.interval ?: it.interval,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ui.update { it.copy(phase = Phase.Failed, error = ErrorText.of(e)) }
            }
        }
    }

    val actions = object : EditActions {
        override fun cancel() {}
        override fun save() = saveChore()
        override fun retry() = load()
        override fun name(v: String) = ui.update { it.copy(name = Rules.cap(v, 80)) }
        override fun category(v: String) = ui.update { it.copy(category = Rules.cap(v, 40)) }
        override fun interval(code: String) = ui.update { it.copy(interval = Intervals.toggle(it.interval, code)) }
        override fun renameAll(on: Boolean) = ui.update { it.copy(renameAll = on) }
        override fun askArchive() = ui.update { it.copy(confirmArchive = true) }
        override fun archive() = archiveChore()
        override fun closeArchive() = ui.update { it.copy(confirmArchive = false) }
    }

    private fun saveChore() {
        val s = ui.value
        val hid = s.householdId ?: return
        if (!s.canSave) return
        ui.update { it.copy(busy = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            try {
                val a = wgc.account() ?: throw ch.tomigorn.wgchores.api.OfflineException()
                val cat = s.category.trim().ifEmpty { null }
                if (choreId == null) {
                    val c = a.api.createChore(hid, s.name.trim(), cat, s.interval)
                    ui.update { it.copy(busy = false, done = EditDone.Created(hid, c.name)) }
                    return@launch
                }
                val renameAll = s.renameAll && s.renameCount > 0
                when (val r = ChoreEditor.save(a.api, choreId, hid, s.chore?.category, s.name.trim(), cat, s.interval, renameAll)) {
                    ChoreEditor.Result.Saved -> ui.update { it.copy(busy = false, done = EditDone.Saved(choreId)) }
                    is ChoreEditor.Result.SaveFailed -> ui.update { it.copy(busy = false, error = r.text) }
                    is ChoreEditor.Result.RenameFailed -> ui.update {
                        it.copy(busy = false, error = R.string.edit_rename_failed, errorDetail = r.text, renameAll = false, chore = it.chore?.copy(name = s.name.trim(), category = cat, interval = s.interval))
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ui.update { it.copy(busy = false, error = ErrorText.of(e, R.string.done_connect)) }
            }
        }
    }

    private fun archiveChore() {
        val c = ui.value.chore ?: return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val a = wgc.account() ?: throw ch.tomigorn.wgchores.api.OfflineException()
                a.api.archiveChore(c.id)
                ui.update { it.copy(busy = false, confirmArchive = false, done = EditDone.Archived(c.householdId, c.name)) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ui.update { it.copy(busy = false, confirmArchive = false, error = ErrorText.of(e, R.string.done_connect)) }
            }
        }
    }

    companion object {
        fun factory(app: Application, choreId: Long?, householdId: Long?): ViewModelProvider.Factory =
            viewModelFactory { initializer { ChoreEditViewModel(app, choreId, householdId) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoreEditScreen(ui: EditUi, act: EditActions) {
    Column(Modifier.fillMaxSize().background(Wgc.colors.bg)) {
        PageBar(stringResource(if (ui.editing) R.string.edit_edit_title else R.string.edit_new_title), act::cancel) {
            BarButton(stringResource(R.string.edit_save), act::save, "edit-save", enabled = ui.canSave, icon = if (narrowScreen()) R.drawable.ic_check else null)
        }
        when (ui.phase) {
            Phase.Loading -> Text(stringResource(R.string.common_loading), color = Wgc.colors.muted, modifier = Modifier.padding(16.dp))
            Phase.Failed, Phase.Gone -> StateMessage(R.drawable.ic_circle_alert, null, stringResource(ui.error ?: R.string.error_generic), stringResource(R.string.common_retry), act::retry)
            Phase.Ready -> Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ui.error?.let { e -> Alert(ui.errorDetail?.let { stringResource(e, stringResource(it)) } ?: stringResource(e)) }
                Field(stringResource(R.string.edit_name), ui.name, act::name, Modifier.testTag("edit-name"))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        stringResource(R.string.edit_category), ui.category, act::category, Modifier.testTag("edit-category"),
                        hint = stringResource(R.string.edit_category_hint), imeAction = ImeAction.Done,
                    )
                    val hits = Rules.categorySuggestions(ui.category, ui.categories)
                    if (hits.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            hits.forEach { h -> Pill(h, selected = false, tag = "cat-$h", radio = false) { act.category(h) } }
                        }
                    }
                    val n = ui.renameCount
                    if (n > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                ui.renameAll, act::renameAll, Modifier.testTag("rename-all"),
                                colors = CheckboxDefaults.colors(checkedColor = Wgc.colors.accent, uncheckedColor = Wgc.colors.borderStrong),
                            )
                            Text(stringResource(R.string.edit_rename_all, ui.chore?.category.orEmpty(), ui.category.trim(), n), style = MaterialTheme.typography.bodyMedium, color = Wgc.colors.text)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.edit_how_often), style = MaterialTheme.typography.labelMedium, color = Wgc.colors.text, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Intervals.ORDER.forEach { code -> Pill(intervalWord(code), ui.interval == code, "iv-$code") { act.interval(code) } }
                    }
                    Text(stringResource(R.string.edit_none_hint), style = MaterialTheme.typography.bodySmall, color = Wgc.colors.muted)
                }
                if (ui.editing) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = Wgc.colors.border)
                        SecondaryButton(stringResource(R.string.edit_archive), act::askArchive, Modifier.padding(top = 8.dp).widthIn(max = 320.dp).testTag("edit-archive"), icon = R.drawable.ic_archive, danger = true)
                        Text(stringResource(R.string.edit_archive_hint), style = MaterialTheme.typography.bodySmall, color = Wgc.colors.muted)
                    }
                }
            }
        }
    }
    if (ui.confirmArchive) {
        ConfirmDialog(
            title = stringResource(R.string.edit_archive_title, ui.chore?.name.orEmpty()),
            text = stringResource(R.string.edit_archive_text),
            confirm = stringResource(R.string.edit_archive_confirm),
            cancel = stringResource(R.string.edit_cancel),
            onConfirm = act::archive,
            onDismiss = act::closeArchive,
            enabled = !ui.busy,
        )
    }
}

@Composable
fun Pill(label: String, selected: Boolean, tag: String, radio: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp).testTag(tag).semantics {
            if (radio) {
                role = Role.RadioButton
                this.selected = selected
            }
        },
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (selected) Wgc.colors.accent else Wgc.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Wgc.colors.accent else Wgc.colors.surface,
            contentColor = if (selected) Wgc.colors.accentText else Wgc.colors.text,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
