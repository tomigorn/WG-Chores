package ch.tomigorn.wgchores.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.DayWord
import ch.tomigorn.wgchores.domain.Words
import java.time.LocalDate
import java.util.Locale

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

@Composable
fun dayText(doneOn: String, today: LocalDate): String = when (val w = Words.dayWord(doneOn, today, currentLocale())) {
    DayWord.Today -> stringResource(R.string.day_today)
    DayWord.Yesterday -> stringResource(R.string.day_yesterday)
    is DayWord.Date -> w.text
}

/** "today", "yesterday", "on 3 Nov" – the snackbar's "done …". */
@Composable
fun doneWhen(doneOn: String, today: LocalDate): String {
    val w = Words.dayWord(doneOn, today, currentLocale())
    return if (w is DayWord.Date) stringResource(R.string.day_on, w.text) else dayText(doneOn, today)
}

@Composable
fun badgeText(c: Chore): String = c.daysSince?.let { stringResource(R.string.badge_days, it) } ?: stringResource(R.string.badge_never)

@Composable
fun badgeLabel(c: Chore): String {
    val d = c.daysSince ?: return stringResource(R.string.badge_never_label)
    val since = pluralStringResource(R.plurals.badge_since, d, d)
    return if (c.daysOverdue > 0) "$since, ${pluralStringResource(R.plurals.badge_overdue, c.daysOverdue, c.daysOverdue)}" else since
}

@Composable
fun noticeText(n: Notice): String = when (n) {
    Notice.Connect -> stringResource(R.string.done_connect)
    Notice.Failed -> stringResource(R.string.done_failed)
    Notice.Offline -> stringResource(R.string.list_offline_note)
    is Notice.Waiting -> pluralStringResource(R.plurals.sync_waiting, n.count, n.count)
    is Notice.Dropped -> listOfNotNull(
        when {
            n.marks > 1 -> stringResource(R.string.done_rejected_many, n.marks)
            n.marks == 1 && n.name != null -> stringResource(R.string.done_rejected, n.name)
            n.marks == 1 -> stringResource(R.string.done_rejected_unknown)
            else -> null
        },
        if (n.undos > 0) pluralStringResource(R.plurals.done_undo_rejected, n.undos, n.undos) else null,
    ).joinToString(" ")
}
