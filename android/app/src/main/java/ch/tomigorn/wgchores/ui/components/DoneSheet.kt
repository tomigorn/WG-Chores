package ch.tomigorn.wgchores.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.ui.list.dayText
import ch.tomigorn.wgchores.ui.theme.Wgc
import java.time.LocalDate
import java.time.ZoneOffset

private const val PICK = -1

/** "Clean toilet – done on": Today / Yesterday / 2 days ago / Pick date, a note, Save. Dates never in the future. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DoneSheetContent(
    name: String,
    today: LocalDate,
    initialDoneOn: String,
    initialNote: String?,
    onSave: (String, String?) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val initialBack = (0..2).firstOrNull { today.minusDays(it.toLong()).toString() == initialDoneOn } ?: PICK
    var choice by rememberSaveable(name, initialDoneOn) { mutableStateOf(initialBack) }
    var picked by rememberSaveable(name, initialDoneOn) { mutableStateOf(initialDoneOn) }
    var note by rememberSaveable(name, initialDoneOn) { mutableStateOf(initialNote.orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    val minDate = today.minusYears(5)
    val doneOn = if (choice == PICK) picked else today.minusDays(choice.toLong()).toString()
    val valid = runCatching { LocalDate.parse(doneOn) }.getOrNull()?.let { !it.isAfter(today) && !it.isBefore(minDate) } == true

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(Modifier.semantics { heading() }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Wgc.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.sheet_done_on), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = Wgc.colors.muted, maxLines = 1, softWrap = false)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val days = listOf(R.string.sheet_today, R.string.sheet_yesterday, R.string.sheet_two_days)
            days.forEachIndexed { i, label -> Chip(stringResource(label), choice == i, "sheet-day-$i") { choice = i } }
            val pickLabel = if (choice == PICK) dayText(picked, today) else stringResource(R.string.sheet_pick)
            Chip(pickLabel, choice == PICK, "sheet-pick", icon = R.drawable.ic_calendar) {
                choice = PICK
                if (LocalDate.parse(picked).isAfter(today)) picked = today.toString()
                picking = true
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.sheet_note), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Wgc.colors.text)
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 500) note = it },
                modifier = Modifier.fillMaxWidth().testTag("sheet-note"),
                placeholder = { Text(stringResource(R.string.sheet_note_placeholder), color = Wgc.colors.muted) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Wgc.colors.surface,
                    unfocusedContainerColor = Wgc.colors.surface,
                    unfocusedBorderColor = Wgc.colors.borderStrong,
                    focusedBorderColor = Wgc.colors.accent,
                ),
            )
        }
        PrimaryButton(stringResource(R.string.sheet_save), onClick = { if (valid) onSave(doneOn, note.trim().ifEmpty { null }) }, enabled = valid, modifier = Modifier.testTag("sheet-save"))
        if (onDelete != null) {
            SecondaryButton(stringResource(R.string.entry_delete), onDelete, Modifier.testTag("sheet-delete"), icon = R.drawable.ic_trash_2, danger = true)
        }
    }

    if (picking) {
        val todayMs = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val minMs = minDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(picked).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = minDate.year..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in minMs..todayMs
                override fun isSelectableYear(year: Int) = year in minDate.year..today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    picking = false
                }, modifier = Modifier.testTag("date-ok")) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.sheet_cancel)) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, icon: Int? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 48.dp).testTag(tag).semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (selected) Wgc.colors.accent else Wgc.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Wgc.colors.accent else Wgc.colors.surface,
            contentColor = if (selected) Wgc.colors.accentText else Wgc.colors.text,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
    }
}
